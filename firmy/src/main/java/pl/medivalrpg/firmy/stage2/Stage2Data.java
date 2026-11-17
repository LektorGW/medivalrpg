// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.stage2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 2 data ({@code kontrakty.json}): contracts with deposits, wholesale orders of NPC rulers and loans. Kept apart
 * from {@code firmy.json}, so a rollback to the stage-1 jar neither reads nor rewrites it (the escrow accounts stay in
 * Skarbiec untouched until the stage-2 jar returns).
 */
public final class Stage2Data {

    /** Who orders and pays. */
    public enum Party { PLAYER, FIRM, COUNTRY, NPC }

    public enum Kind { GOODS, SERVICE }

    public enum ContractState {
        /** Payment in escrow, waiting for a contractor (or the named one). */
        OPEN("czeka na wykonawcę"),
        /** Taken by a company (its bond is in the escrow too). */
        ACCEPTED("w realizacji"),
        /** Service reported as done, waiting for the client's confirmation. */
        REPORTED("zgłoszony jako wykonany"),
        DISPUTED("spór — czeka na administratora"),
        DONE("wykonany"),
        FAILED("niedotrzymany termin"),
        CANCELLED("odwołany"),
        RESOLVED("rozstrzygnięty");

        private final String label;

        ContractState(final String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }

        public boolean open() {
            return this == OPEN || this == ACCEPTED || this == REPORTED || this == DISPUTED;
        }
    }

    public static final class Contract {
        public static final String ACCOUNT_PREFIX = "firma-kontrakt:";

        public String id;
        public String title;
        public Kind kind = Kind.GOODS;
        public Party client;
        /** Player uuid, firm id or country id of the client. */
        public String clientId;
        public String clientName;
        /** Account the payment came from and refunds go back to. */
        public String clientAccount;
        /** Player who created it (for notifications). */
        public String createdBy;
        /** Firm id of the contractor (empty while OPEN for anybody). */
        public String contractor;
        public String contractorName;
        /** Only this firm may take it (empty = any). */
        public String reservedFor;
        public String item;
        public int quantity;
        public int delivered;
        /** Receiving chest {@code world:x:y:z} (goods for players/firms/countries); null for NPC orders (capital). */
        public String chest;
        public long price;
        public long bond;
        public int hours;
        public long createdAt;
        public long acceptedAt;
        public long deadline;
        /** Offer validity before anybody takes it. */
        public long offerUntil;
        public long reportedAt;
        public ContractState state = ContractState.OPEN;
        public String disputeReason;
        public String disputeBy;
        public long disputeAt;
        public String resolution;
        /** NPC order: the delivering player gets the reputation. */
        public String deliveredBy;
        public boolean funded;
        public boolean bondPaid;
        public List<String> log = new ArrayList<>();

        public String account() {
            return ACCOUNT_PREFIX + this.id;
        }
    }

    public enum LoanState {
        /** World Treasury loan waiting for the owner's manual decision (no money moved). */
        REQUESTED("czeka na zgodę właściciela"),
        /** Lender's money in escrow, waiting for the borrower's master. */
        OFFERED("oferta (pieniądze w depozycie)"),
        ACTIVE("spłacany"),
        REPAID("spłacony"),
        DEFAULTED("niespłacony (likwidacja)"),
        CANCELLED("wycofany"),
        REJECTED("odrzucony");

        private final String label;

        LoanState(final String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }

        public boolean live() {
            return this == REQUESTED || this == OFFERED || this == ACTIVE;
        }
    }

    public enum Lender { PLAYER, FIRM, WORLD }

    public static final class Loan {
        public static final String ACCOUNT_PREFIX = "firma-kredyt:";

        public String id;
        public String firm;
        public String firmName;
        public Lender lender;
        /** Player uuid, firm id, or "world". */
        public String lenderId;
        public String lenderName;
        /** Where repayments go: player:<uuid>, firma:<id>, or this loan's escrow for World Treasury loans. */
        public String repayTo;
        public long principal;
        /** Interest for the WHOLE period in basis points (≤ 1000 = 10 %). */
        public int interestBp;
        public int days;
        public String purpose = "";
        public long createdAt;
        public long offerUntil;
        public long disbursedAt;
        public long dueAt;
        public long repaid;
        public LoanState state;
        public String decidedBy;
        public String note = "";
        /** World loans: copper already returned from the escrow to the reserve. */
        public long returnedToReserve;
        public List<String> log = new ArrayList<>();

        public String account() {
            return ACCOUNT_PREFIX + this.id;
        }

        public long total() {
            return this.principal + Math.multiplyExact(this.principal, (long) this.interestBp) / 10_000L;
        }

        public long outstanding() {
            return Math.max(0, this.total() - this.repaid);
        }
    }

    public int version = 1;
    public Map<String, Contract> contracts = new LinkedHashMap<>();
    public Map<String, Loan> loans = new LinkedHashMap<>();
    public String lastWholesale = "";
    /** Copper funded today from each NPC treasury for wholesale orders (own limit when Wladcy has no bridge). */
    public String npcDay = "";
    public Map<String, Long> npcSpent = new LinkedHashMap<>();

    public void npcBooked(final String day, final String npc, final long amount) {
        if (!day.equals(this.npcDay)) {
            this.npcDay = day;
            this.npcSpent.clear();
        }
        this.npcSpent.merge(npc, amount, Long::sum);
    }

    public long npcBookedToday(final String day, final String npc) {
        return day.equals(this.npcDay) ? this.npcSpent.getOrDefault(npc, 0L) : 0L;
    }
}
