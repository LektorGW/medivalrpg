// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.LudnoscPlugin;

class SupplyDeliveryBridgeTest {
    @Test void optionalReflectionMethodHasTheAgreedSignatureWithoutSharedApiTypes() throws Exception {
        var method=LudnoscPlugin.class.getMethod("supplyDelivery",UUID.class,UUID.class,String.class,String.class,int.class);
        assertTrue(Modifier.isPublic(method.getModifiers())); assertEquals(Map.class,method.getReturnType());
        assertEquals("java.util.Map<java.lang.String, java.lang.Object>",method.getGenericReturnType().getTypeName());
    }
}
