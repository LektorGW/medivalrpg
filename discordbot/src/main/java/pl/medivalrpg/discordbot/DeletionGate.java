// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;
import java.util.*;
import java.io.IOException;
public final class DeletionGate {
    @FunctionalInterface public interface Action{void run()throws Exception;}
    public static void afterExport(boolean required,List<HistoryExporter.Result> receipts,Action delete)throws Exception {
        if(required&&receipts.isEmpty())throw new IOException("Brak potwierdzonego eksportu.");
        for(var receipt:receipts)if(!receipt.verify())throw new IOException("Niezgodny eksport; kanał pozostaje.");
        delete.run();
    }
}
