// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.command;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.brigadier.CommandDispatcher;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class RoyalCommandTreeTest {
    private CommandSourceStack source(boolean admin) {
        final var sender=(CommandSender)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{CommandSender.class},(p,m,a)->switch(m.getName()){
            case "hasPermission" -> admin||"ludnosc.use".equals(a[0]);case "getName" -> "Tester";default -> throw new UnsupportedOperationException(m.getName());});
        return (CommandSourceStack)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{CommandSourceStack.class},(p,m,a)->switch(m.getName()){
            case "getSender" -> sender;default -> throw new UnsupportedOperationException(m.getName());});
    }
    @Test void reviewedCommandsUseCorrectSpellingAndRequireAdmin() {
        final var tree=new LudnoscCommandTree(null).build();final var admin=tree.getChild("admin");
        assertTrue(admin.canUse(source(true)));assertFalse(admin.canUse(source(false)));
        final var royal=admin.getChild("krolestwo");assertNotNull(royal);assertNotNull(admin.getChild("krolewstwo"));
        assertEquals(Set.of("preview","start","status","resume"),royal.getChildren().stream().map(n->n.getName()).collect(Collectors.toSet()));
        final var dispatcher=new CommandDispatcher<CommandSourceStack>();dispatcher.getRoot().addChild(tree);
        for(String action:Set.of("preview","start","status","resume"))assertFalse(dispatcher.parse("ludnosc admin krolestwo "+action,source(true)).getReader().canRead());
        assertTrue(dispatcher.parse("ludnosc admin krolestwo start",source(false)).getReader().canRead());
    }
}
