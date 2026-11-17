// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Small durable control markers, independent of jobs.json and the financial journal. */
final class NetworkPause {
    private final Path folder;
    private volatile boolean paused;
    NetworkPause(Path folder) {
        this.folder=folder;
        // An interrupted marker write is a stop request too (fail closed).
        paused=Files.exists(folder.resolve("paused")) || Files.exists(folder.resolve("paused.tmp"));
    }
    boolean paused(){return paused;}
    void stopInMemory(){paused=true;}
    void pause() throws IOException {
        paused=true; // Even a failed write must stop this process.
        write("paused","Budowa sieci wstrzymana przez zarządcę.\n");
    }
    void resume(String requestSha) throws IOException {
        if(requestSha==null || !requestSha.matches("[a-f0-9]{64}"))throw new IOException("Brak poprawnego planu sieci.");
        // Authorize the exact request first. A crash before clearing paused remains safe.
        write("start.ready",requestSha+"\n");
        Files.deleteIfExists(folder.resolve("paused.tmp"));
        Files.deleteIfExists(folder.resolve("paused"));
        paused=false;
    }
    private void write(String name,String value) throws IOException {
        Files.createDirectories(folder);
        Path target=folder.resolve(name),tmp=folder.resolve(name+".tmp");
        try(var channel=FileChannel.open(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
            var bytes=StandardCharsets.UTF_8.encode(value);
            while(bytes.hasRemaining())channel.write(bytes);
            channel.force(true);
        }
        Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
}
