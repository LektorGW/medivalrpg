// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.storage;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import pl.medivalrpg.ludnosc.logic.SupplyLedger;

/** Retained per-contract receipts. Never restores an older backup as permission to transfer again. */
public final class SupplyJournal implements SupplyLedger.Journal, AutoCloseable {
    private final Path directory;
    private final Gson gson = new Gson();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "Ludnosc-supply-journal"); t.setDaemon(true); return t; });
    public SupplyJournal(Path directory) { this.directory = directory; }
    public static String fileName(String id) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8))) + ".json"; }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public List<SupplyLedger.Entry> load() throws IOException {
        if (!Files.exists(directory)) return List.of();
        List<Path> paths;
        try (var stream = Files.list(directory)) { paths = stream.limit(SupplyLedger.MAX_RECORDS * 4L + 1).sorted().toList(); }
        if (paths.size() > SupplyLedger.MAX_RECORDS * 4) throw new IOException("Zbyt wiele plików dziennika.");
        var out = new ArrayList<SupplyLedger.Entry>();
        for (Path p : paths) {
            String name = p.getFileName().toString();
            int marker = name.indexOf(".json.");
            if (marker >= 0 && !Files.exists(directory.resolve(name.substring(0, marker + 5))))
                throw new IOException("Osierocony plik backup/tmp dostawy — wymagany audyt.");
            if (!name.endsWith(".json")) continue;
            if (!Files.isRegularFile(p) || Files.size(p) > 262_144) throw new IOException("Nieprawidłowy plik dostawy.");
            try {
                SupplyLedger.Entry e = gson.fromJson(Files.readString(p, StandardCharsets.UTF_8), SupplyLedger.Entry.class);
                if (e == null || !fileName(e.payload().contractId()).equals(name)) throw new IllegalArgumentException("Niezgodny identyfikator pliku.");
                out.add(e);
            } catch (RuntimeException ex) { throw new IOException("Uszkodzony dziennik dostaw: " + name, ex); }
        }
        if (out.size() > SupplyLedger.MAX_RECORDS) throw new IOException("Przekroczony limit pokwitowań.");
        return List.copyOf(out);
    }
    @Override public CompletableFuture<Void> write(SupplyLedger.Entry entry) {
        String json = gson.toJson(entry);
        return CompletableFuture.runAsync(() -> {
            try {
                if (json.getBytes(StandardCharsets.UTF_8).length > 262_144) throw new IOException("Zbyt duży zapis dostawy.");
                Files.createDirectories(directory);
                Path file = directory.resolve(fileName(entry.payload().contractId()));
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
                Files.writeString(tmp, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                force(tmp);
                if (Files.exists(file)) {
                    Path bak = file.resolveSibling(file.getFileName() + ".bak");
                    Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING); force(bak);
                }
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
                    try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
            } catch (IOException ex) { throw new UncheckedIOException(ex); }
        }, writer);
    }
    private static void force(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
    }
    @Override public void close() {
        writer.shutdown();
        try { writer.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
