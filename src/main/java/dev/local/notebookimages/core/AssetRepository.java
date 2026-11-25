package dev.local.notebookimages.core;

import com.google.gson.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;

/** Transactional ownership inventory. Unmanaged or externally modified images are never removed. */
public final class AssetRepository {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern NUMBERED = Pattern.compile("(?i)^img_(\\d+)\\.png$");
    private final PathPolicy paths;
    private final Path stateDirectory;
    private final Path manifest;

    public record Asset(String id, String path, String notebook, String sha256,
                        int width, int height, long createdAt, boolean trashed, boolean keepUntilReferenced) {}
    public record Report(int restored, int trashed, List<String> problems) {}

    private static final class Entry {
        String id, path, notebook, sha256;
        int width, height;
        long createdAt;
        boolean trashed;
        boolean keepUntilReferenced;
        Asset view() { return new Asset(id, path, notebook, sha256, width, height, createdAt, trashed, keepUntilReferenced); }
    }
    private static final class Inventory {
        int formatVersion = 1;
        Map<String, Integer> counters = new LinkedHashMap<>();
        List<Entry> assets = new ArrayList<>();
    }

    public AssetRepository(Path root) throws IOException {
        paths = new PathPolicy(root);
        stateDirectory = paths.resolve(PathPolicy.STATE_DIRECTORY);
        manifest = stateDirectory.resolve("manifest.json");
    }
    public PathPolicy paths() { return paths; }
    public boolean exists() { return Files.isRegularFile(manifest); }
    public synchronized List<Asset> assets() throws IOException { return load().assets.stream().map(Entry::view).toList(); }
    public Path path(Asset asset) throws IOException { return paths.stored(asset.path()); }
    public Path trashPath(Asset asset) throws IOException { return paths.validate(stateDirectory.resolve("trash").resolve(asset.id() + ".png")); }

    public synchronized String nextName(String directory) throws IOException {
        Path folder = paths.imageDirectory(directory);
        return String.format(Locale.ROOT, "img_%04d.png", nextNumber(folder, load()));
    }

    public synchronized Asset save(ImageData image, Path notebook, String directory, String requestedName) throws IOException {
        Path folder = paths.imageDirectory(directory);
        String notebookRelative = paths.relative(notebook);
        String fileName = PathPolicy.fileName(requestedName);
        return locked(inventory -> {
            int next = nextNumber(folder, inventory);
            Path destination = paths.validate(folder.resolve(fileName));
            String relative = paths.relative(destination);
            if (inventory.assets.stream().anyMatch(e -> e.path.equals(relative))) {
                throw new IOException("This filename already has a management record. Use a new image number.");
            }
            Files.createDirectories(folder);
            paths.validate(folder);
            Files.write(destination, image.png(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Entry entry = new Entry();
            entry.id = UUID.randomUUID().toString();
            entry.path = relative;
            entry.notebook = notebookRelative;
            entry.sha256 = digest(image.png());
            entry.width = image.width();
            entry.height = image.height();
            entry.createdAt = Instant.now().toEpochMilli();
            inventory.assets.add(entry);
            Matcher number = NUMBERED.matcher(fileName);
            if (number.matches()) {
                try { next = Math.max(next, Math.addExact(Integer.parseInt(number.group(1)), 1)); }
                catch (ArithmeticException | NumberFormatException e) { next = Integer.MAX_VALUE; }
            } else next++;
            inventory.counters.put(paths.relative(folder), next);
            try { persist(inventory); }
            catch (IOException error) {
                if (Files.exists(destination) && digest(destination).equals(entry.sha256)) Files.delete(destination);
                throw error;
            }
            return entry.view();
        });
    }

    /** Reversible removal; the source must still match the bytes that this plugin created. */
    public synchronized boolean trash(String id) throws IOException {
        return locked(inventory -> {
            Entry entry = find(inventory, id);
            if (entry.trashed) return false;
            Path source = paths.stored(entry.path);
            if (!Files.exists(source)) return false;
            if (!digest(source).equals(entry.sha256)) throw new IOException("The image was modified by another tool and was kept: " + entry.path);
            Path destination = trashPath(entry.view());
            Files.createDirectories(destination.getParent());
            if (Files.exists(destination)) {
                if (!digest(destination).equals(entry.sha256)) throw new IOException("A different file with the same name exists in the recycle area. The original image was kept.");
                Files.delete(destination);
            }
            Files.move(source, destination);
            entry.trashed = true;
            try { persist(inventory); }
            catch (IOException error) { Files.move(destination, source); throw error; }
            return true;
        });
    }

    public synchronized boolean restore(String id) throws IOException {
        return restore(id, false);
    }

    public synchronized boolean restore(String id, boolean keepUntilReferenced) throws IOException {
        return locked(inventory -> {
            Entry entry = find(inventory, id);
            if (!entry.trashed) {
                if (keepUntilReferenced) { entry.keepUntilReferenced = true; persist(inventory); }
                return false;
            }
            Path source = trashPath(entry.view());
            Path destination = paths.stored(entry.path);
            if (!Files.isRegularFile(source) || !digest(source).equals(entry.sha256)) {
                throw new IOException("The recycled image is missing or has been modified: " + entry.path);
            }
            if (Files.exists(destination)) {
                if (!digest(destination).equals(entry.sha256)) throw new IOException("A file with the same name already exists and was not overwritten during recovery: " + entry.path);
                entry.trashed = false;
                entry.keepUntilReferenced = keepUntilReferenced;
                persist(inventory);
                // Retain the backup; it is harmless and makes recovery idempotent after a crash.
                return true;
            }
            Files.createDirectories(destination.getParent());
            paths.validate(destination);
            Files.move(source, destination);
            entry.trashed = false;
            entry.keepUntilReferenced = keepUntilReferenced;
            try { persist(inventory); }
            catch (IOException error) { Files.move(destination, source); throw error; }
            return true;
        });
    }

    /** Used only when insertion failed before a command was recorded. */
    public synchronized void discardNewAsset(String id) throws IOException {
        locked(inventory -> {
            Entry entry = find(inventory, id);
            Path image = entry.trashed ? trashPath(entry.view()) : paths.stored(entry.path);
            // Keep a recoverable backup if a failure occurs while rolling back an insertion.
            if (!entry.trashed && Files.exists(image) && digest(image).equals(entry.sha256)) {
                Path backup = trashPath(entry.view());
                Files.createDirectories(backup.getParent());
                Files.move(image, backup);
                entry.trashed = true;
                persist(inventory);
            }
            return null;
        });
    }

    public Report reconcile(ReferenceScanner.Result references, boolean cleanup, BooleanSupplier stillCurrent) throws IOException {
        int restored = 0, trashed = 0;
        List<String> problems = new ArrayList<>(references.problems());
        for (Asset asset : assets()) {
            if (!stillCurrent.getAsBoolean()) break;
            try {
                boolean used = references.references().contains(path(asset));
                if (used && asset.trashed()) { if (restore(asset.id())) restored++; }
                if (used && asset.keepUntilReferenced()) {
                    synchronized (this) {
                        locked(inventory -> { find(inventory, asset.id()).keepUntilReferenced = false; persist(inventory); return null; });
                    }
                } else if (cleanup && references.complete() && !used && !asset.trashed() && !asset.keepUntilReferenced()) {
                    if (trash(asset.id())) trashed++;
                }
            } catch (IOException e) { problems.add(e.getMessage()); }
        }
        return new Report(restored, trashed, List.copyOf(problems));
    }

    private int nextNumber(Path directory, Inventory inventory) throws IOException {
        int next = Math.max(1, inventory.counters.getOrDefault(paths.relative(directory), 1));
        if (Files.isDirectory(directory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path child : stream) {
                    Matcher matcher = NUMBERED.matcher(child.getFileName().toString());
                    if (matcher.matches()) {
                        try { next = Math.max(next, Math.addExact(Integer.parseInt(matcher.group(1)), 1)); }
                        catch (NumberFormatException | ArithmeticException ignored) {}
                    }
                }
            }
        }
        if (next >= Integer.MAX_VALUE) throw new IOException("The image numbering limit has been reached. Choose a new folder.");
        return next;
    }

    private Inventory load() throws IOException {
        paths.validate(manifest);
        if (!Files.exists(manifest)) {
            if (Files.isDirectory(stateDirectory.resolve("trash"))) {
                try (var stream = Files.list(stateDirectory.resolve("trash"))) {
                    if (stream.findAny().isPresent()) throw new IOException("Management records are missing. The recycle area was kept; restore manifest.json first.");
                }
            }
            return new Inventory();
        }
        if (Files.size(manifest) > 16L * 1024 * 1024) throw new IOException("The image management records are too large.");
        try {
            JsonObject serialized = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
            if (!serialized.has("formatVersion") || !serialized.has("assets") || !serialized.has("counters")) {
                throw new IOException("The image management records are incomplete. File operations have stopped.");
            }
            Inventory inventory = JSON.fromJson(serialized, Inventory.class);
            if (inventory == null || inventory.formatVersion != 1 || inventory.assets == null || inventory.counters == null) {
                throw new IOException("The image management records are invalid. File operations have stopped.");
            }
            Set<String> ids = new HashSet<>(), names = new HashSet<>();
            for (Entry entry : inventory.assets) {
                if (entry == null || entry.id == null || !entry.id.matches("[0-9a-f-]{36}") ||
                    entry.sha256 == null || !entry.sha256.matches("[0-9a-f]{64}") ||
                    !ids.add(entry.id) || !names.add(entry.path)) throw new IOException("The image management records are corrupted.");
                Path image = paths.stored(entry.path);
                if (image.startsWith(stateDirectory) || !image.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
                    throw new IOException("An image path in the management records is invalid.");
                }
                paths.stored(entry.notebook);
                // Recover a process interruption between a reversible move and manifest replacement.
                Path backup = trashPath(entry.view());
                if (!entry.trashed && !Files.exists(image) && Files.isRegularFile(backup) && digest(backup).equals(entry.sha256)) {
                    entry.trashed = true;
                } else if (entry.trashed && Files.isRegularFile(image) && !Files.exists(backup) && digest(image).equals(entry.sha256)) {
                    entry.trashed = false;
                }
            }
            return inventory;
        } catch (JsonParseException | IllegalStateException | NullPointerException | InvalidPathException e) {
            throw new IOException("The image management records are corrupted. Files were kept; repair manifest.json to continue.", e);
        }
    }

    private void persist(Inventory inventory) throws IOException {
        paths.validate(stateDirectory);
        Path temporary = stateDirectory.resolve("manifest-" + UUID.randomUUID() + ".tmp");
        try {
            byte[] bytes = JSON.toJson(inventory).getBytes(StandardCharsets.UTF_8);
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            try { Files.move(temporary, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static Entry find(Inventory inventory, String id) throws IOException {
        return inventory.assets.stream().filter(e -> e.id.equals(id)).findFirst()
            .orElseThrow(() -> new IOException("The image has no management record."));
    }
    private interface Operation<T> { T apply(Inventory inventory) throws IOException; }
    private <T> T locked(Operation<T> operation) throws IOException {
        paths.validate(stateDirectory);
        Files.createDirectories(stateDirectory);
        Path lockPath = paths.validate(stateDirectory.resolve("inventory.lock"));
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return operation.apply(load());
        } catch (OverlappingFileLockException e) {
            throw new IOException("Another window is managing images in this project. Try again shortly.", e);
        }
    }
    public static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String digest(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
