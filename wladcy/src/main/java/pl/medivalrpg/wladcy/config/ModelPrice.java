// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.config;

/**
 * Price list of one model in USD per million tokens ({@code pricing.<model>} in config.yml).
 *
 * @param input      uncached input tokens
 * @param output     output tokens (thinking included)
 * @param cacheRead  input tokens read from the prompt cache
 * @param cacheWrite input tokens written to the 5-minute prompt cache
 */
public record ModelPrice(double input, double output, double cacheRead, double cacheWrite) {

    /** Claude Opus 5.5 list price (used when the config has no entry at all). */
    public static final ModelPrice OPUS_5_5 = new ModelPrice(4.00, 20.00, 0.20, 5.00);
}
