// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.glos;

/**
 * Stan głosu jednego gracza online. Modyfikowany wyłącznie na głównym wątku;
 * pola są volatile, bo odczytuje je też np. podpowiadanie komend.
 */
public final class VoiceState {
    private volatile VoiceMode baseMode = VoiceMode.NORMAL;
    private volatile boolean speaking;

    public VoiceMode baseMode() {
        return baseMode;
    }

    public void setBaseMode(VoiceMode baseMode) {
        this.baseMode = baseMode;
    }

    public boolean speaking() {
        return speaking;
    }

    public void setSpeaking(boolean speaking) {
        this.speaking = speaking;
    }
}
