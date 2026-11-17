// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.model;

import java.util.List;
import java.util.UUID;

/** Exact container identity/topology; a double chest is one store, with both halves validated. */
public record ProductionBinding(UUID token, List<BlockPos> parts) { }
