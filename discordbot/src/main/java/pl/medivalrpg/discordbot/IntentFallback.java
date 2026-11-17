// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;
/** One bounded downgrade, never a login loop. The second 4014 normally means Members Intent is absent. */
public final class IntentFallback {
    private boolean retried;
    public synchronized boolean retryWithoutContent(int code,boolean requestedContent) {
        if(code!=4014 || !requestedContent || retried)return false;
        retried=true;return true;
    }
}
