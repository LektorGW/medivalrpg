// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.ruler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import pl.medivalrpg.armia.ai.NpcAiService;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.config.Messages;
import pl.medivalrpg.armia.logic.Personality;
import pl.medivalrpg.armia.model.NpcState;
import pl.medivalrpg.armia.service.Services;
import pl.medivalrpg.armia.soldier.ArmyService;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;

/** Opens the ruler's diplomacy window and handles its clicks. Every inventory action in it is cancelled. */
public final class GuiListener implements Listener {

    private final RulerService rulers;
    private final NpcAiService ai;
    private final ArmyService army;
    private final Services services;
    private final Supplier<ArmiaConfig> config;
    private final Supplier<Messages> messages;
    private final Map<UUID, Long> lastOpen = new HashMap<>();

    public GuiListener(final RulerService rulers, final NpcAiService ai, final ArmyService army, final Services services,
                       final Supplier<ArmiaConfig> config, final Supplier<Messages> messages) {
        this.rulers = rulers;
        this.ai = ai;
        this.army = army;
        this.services = services;
        this.config = config;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(final PlayerInteractEntityEvent event) {
        this.interact(event.getPlayer(), event.getRightClicked(), event.getHand(), event);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractAt(final PlayerInteractAtEntityEvent event) {
        this.interact(event.getPlayer(), event.getRightClicked(), event.getHand(), event);
    }

    private void interact(final Player p, final org.bukkit.entity.Entity clicked, final EquipmentSlot hand, final org.bukkit.event.Cancellable event) {
        final Optional<UUID> npc = this.rulers.countryOf(clicked);
        if (npc.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        if (hand != EquipmentSlot.HAND) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Long last = this.lastOpen.get(p.getUniqueId());
        if (last != null && now - last < 400) {
            return;
        }
        this.lastOpen.put(p.getUniqueId(), now);
        this.open(p, npc.get(), DiplomacyGui.Screen.MAIN);
    }

    // ------------------------------------------------------------------ screens

    public void open(final Player p, final UUID npc, final DiplomacyGui.Screen screen) {
        final Optional<KrajInfo> oc = this.services.kraje().getCountry(npc);
        if (oc.isEmpty()) {
            return;
        }
        final KrajInfo c = oc.get();
        final Messages m = this.messages.get();
        final Optional<KrajInfo> own = this.services.kraje().getCountryOf(p.getUniqueId());
        final Rank rank = this.services.kraje().getRank(p.getUniqueId()).orElse(Rank.CITIZEN);
        final boolean diplomat = own.isPresent() && !own.get().id().equals(npc) && rank.atLeast(this.config.get().diplomacyRank);
        final Component countryName = Component.text(c.name(), c.color());
        final DiplomacyGui gui = new DiplomacyGui(npc, p.getUniqueId(), diplomat, screen,
            m.get(screen == DiplomacyGui.Screen.MAIN ? "gui-title" : "gui-tribute-title", Placeholder.component("country", countryName)),
            this.config.get().tributeAmounts);
        if (screen == DiplomacyGui.Screen.MAIN) {
            this.fillMain(gui, c, own.orElse(null), diplomat);
        } else {
            this.fillTribute(gui, c);
        }
        gui.fill(Material.GRAY_STAINED_GLASS_PANE);
        p.openInventory(gui.getInventory());
    }

    private void fillMain(final DiplomacyGui gui, final KrajInfo c, final KrajInfo own, final boolean diplomat) {
        final Messages m = this.messages.get();
        final NpcState st = this.ai.state(c.id()).orElse(null);
        final Personality pers = st == null ? this.config.get().aiDefaults : st.personality();
        final Relation rel = own == null ? Relation.NEUTRAL : this.services.kraje().getRelation(own.id(), c.id());
        final int opinion = own == null || st == null ? 0 : st.opinionOf(own.id());
        final TagResolver[] tags = {
            Placeholder.component("country", Component.text(c.name(), c.color())),
            Placeholder.unparsed("ruler", c.rulerName() == null ? "?" : c.rulerName()),
            Placeholder.unparsed("chunks", Integer.toString(c.chunkCount())),
            Placeholder.unparsed("soldiers", Integer.toString(this.army.registry().count(c.id()))),
            Placeholder.unparsed("population", Integer.toString(this.services.population().population(c.id()))),
            Placeholder.unparsed("relation", own == null ? m.plain("gui-relation-none") : rel.displayName()),
            Placeholder.unparsed("opinion", this.opinionWord(opinion)),
            Placeholder.unparsed("character", this.character(pers)),
            Placeholder.unparsed("allies", Integer.toString(c.allies().size())),
            Placeholder.unparsed("enemies", Integer.toString(c.enemies().size()))};
        gui.set(DiplomacyGui.SLOT_INFO, DiplomacyGui.item(m, Material.WRITTEN_BOOK, "gui-info", tags));
        if (diplomat) {
            gui.set(DiplomacyGui.SLOT_ALLIANCE, rel == Relation.ALLIANCE
                ? DiplomacyGui.item(m, Material.LIME_DYE, "gui-alliance-have", tags)
                : rel == Relation.WAR ? DiplomacyGui.item(m, Material.GRAY_DYE, "gui-alliance-war", tags)
                : DiplomacyGui.item(m, Material.EMERALD, "gui-alliance", tags));
            gui.set(DiplomacyGui.SLOT_PEACE, rel == Relation.WAR
                ? DiplomacyGui.item(m, Material.WHITE_BANNER, "gui-peace", tags)
                : DiplomacyGui.item(m, Material.GRAY_DYE, "gui-peace-none", tags));
            gui.set(DiplomacyGui.SLOT_TRIBUTE, DiplomacyGui.item(m, Material.GOLD_INGOT, "gui-tribute", tags));
            gui.set(DiplomacyGui.SLOT_WAR, rel == Relation.WAR
                ? DiplomacyGui.item(m, Material.GRAY_DYE, "gui-war-already", tags)
                : DiplomacyGui.item(m, Material.IRON_SWORD, "gui-war", tags));
        } else {
            gui.set(13, DiplomacyGui.item(m, Material.PAPER, "gui-not-diplomat",
                Placeholder.unparsed("rank", this.config.get().diplomacyRank.displayName())));
        }
        if (talkAvailable()) {
            gui.set(DiplomacyGui.SLOT_TALK, DiplomacyGui.item(m, Material.WRITABLE_BOOK, "gui-talk", tags));
        }
        gui.set(DiplomacyGui.SLOT_CLOSE, DiplomacyGui.item(m, Material.BARRIER, "gui-close"));
    }

    /** Conversations with rulers come from the optional Wladcy plugin (no hard dependency). */
    private static boolean talkAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("Wladcy");
    }

    private void fillTribute(final DiplomacyGui gui, final KrajInfo c) {
        final Messages m = this.messages.get();
        final List<Long> amounts = gui.amounts;
        for (int i = 0; i < Math.min(amounts.size(), DiplomacyGui.TRIBUTE_SLOTS.length); i++) {
            gui.set(DiplomacyGui.TRIBUTE_SLOTS[i], DiplomacyGui.item(m, i == 0 ? Material.IRON_NUGGET : i == 1 ? Material.GOLD_NUGGET : Material.GOLD_INGOT,
                "gui-tribute-amount", Placeholder.unparsed("amount", this.services.money(amounts.get(i))),
                Placeholder.component("country", Component.text(c.name(), c.color()))));
        }
        gui.set(DiplomacyGui.SLOT_CLOSE, DiplomacyGui.item(m, Material.ARROW, "gui-back"));
    }

    private String opinionWord(final int opinion) {
        final Messages m = this.messages.get();
        if (opinion <= -40) {
            return m.plain("opinion-hostile");
        }
        if (opinion < -10) {
            return m.plain("opinion-cold");
        }
        if (opinion <= 10) {
            return m.plain("opinion-neutral");
        }
        if (opinion < 40) {
            return m.plain("opinion-friendly");
        }
        return m.plain("opinion-warm");
    }

    private String character(final Personality p) {
        final Messages m = this.messages.get();
        final List<String> traits = new ArrayList<>();
        if (p.aggression() >= 70) {
            traits.add(m.plain("trait-aggressive"));
        } else if (p.aggression() <= 30) {
            traits.add(m.plain("trait-peaceful"));
        }
        if (p.diplomacy() >= 70) {
            traits.add(m.plain("trait-diplomatic"));
        }
        if (p.expansion() >= 70) {
            traits.add(m.plain("trait-expansive"));
        }
        if (p.wealth() >= 70) {
            traits.add(m.plain("trait-rich"));
        }
        return traits.isEmpty() ? m.plain("trait-balanced") : String.join(", ", traits);
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof DiplomacyGui gui)) {
            return;
        }
        event.setCancelled(true); // blocks shift-click, number keys, double click, drop
        if (event.getClickedInventory() != event.getView().getTopInventory() || !(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        if (!p.getUniqueId().equals(gui.viewer)) {
            return;
        }
        final int slot = event.getRawSlot();
        final Optional<KrajInfo> oc = this.services.kraje().getCountry(gui.npc);
        if (oc.isEmpty()) {
            p.closeInventory();
            return;
        }
        final String name = oc.get().name();
        if (gui.screen == DiplomacyGui.Screen.TRIBUTE) {
            if (slot == DiplomacyGui.SLOT_CLOSE) {
                this.open(p, gui.npc, DiplomacyGui.Screen.MAIN);
                return;
            }
            for (int i = 0; i < DiplomacyGui.TRIBUTE_SLOTS.length && i < gui.amounts.size(); i++) {
                if (DiplomacyGui.TRIBUTE_SLOTS[i] == slot && gui.diplomat) {
                    this.ai.tribute(p, gui.npc, gui.amounts.get(i));
                    this.open(p, gui.npc, DiplomacyGui.Screen.MAIN);
                    return;
                }
            }
            return;
        }
        if (slot == DiplomacyGui.SLOT_CLOSE) {
            p.closeInventory();
            return;
        }
        if (slot == DiplomacyGui.SLOT_TALK && talkAvailable()) {
            p.closeInventory();
            p.performCommand("wladca rozmowa");
            return;
        }
        if (!gui.diplomat) {
            return;
        }
        final Optional<KrajInfo> own = this.services.kraje().getCountryOf(p.getUniqueId());
        final Relation rel = own.map(o -> this.services.kraje().getRelation(o.id(), gui.npc)).orElse(Relation.NEUTRAL);
        switch (slot) {
            case DiplomacyGui.SLOT_ALLIANCE -> {
                if (rel == Relation.NEUTRAL) {
                    p.closeInventory();
                    p.performCommand("kraj sojusz " + name);
                }
            }
            case DiplomacyGui.SLOT_PEACE -> {
                if (rel == Relation.WAR) {
                    p.closeInventory();
                    p.performCommand("kraj pokoj " + name);
                }
            }
            case DiplomacyGui.SLOT_TRIBUTE -> this.open(p, gui.npc, DiplomacyGui.Screen.TRIBUTE);
            case DiplomacyGui.SLOT_WAR -> {
                if (rel == Relation.WAR) {
                    return;
                }
                final long now = System.currentTimeMillis();
                if (now > gui.warConfirmUntil) {
                    gui.warConfirmUntil = now + 5000;
                    gui.set(DiplomacyGui.SLOT_WAR, DiplomacyGui.item(this.messages.get(), Material.NETHERITE_SWORD, "gui-war-confirm",
                        Placeholder.component("country", Component.text(name, oc.get().color()))));
                    return;
                }
                p.closeInventory();
                p.performCommand("kraj wojna " + name);
            }
            default -> {
                // filler
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(final InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof DiplomacyGui) {
            event.setCancelled(true);
        }
    }
}
