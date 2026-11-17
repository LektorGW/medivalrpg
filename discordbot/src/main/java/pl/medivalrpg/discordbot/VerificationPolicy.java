// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;
import java.time.*;
public final class VerificationPolicy {
    public static boolean oldEnough(Instant created,Instant now,int minimumDays){return minimumDays<=0||Duration.between(created,now).toDays()>=minimumDays;}
}
