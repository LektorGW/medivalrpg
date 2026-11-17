// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.cost;

/**
 * Token usage of one model attempt, independent of the SDK types (see {@code engine.UsageMapper}).
 *
 * @param model      model that ran the attempt (a fallback model may differ from the requested one)
 * @param input      uncached input tokens
 * @param output     output tokens (thinking included)
 * @param cacheRead  input tokens read from the prompt cache
 * @param cacheWrite input tokens written to the prompt cache
 */
public record UsageSample(String model, long input, long output, long cacheRead, long cacheWrite) {

    public long totalInput() {
        return this.input + this.cacheRead + this.cacheWrite;
    }
}
