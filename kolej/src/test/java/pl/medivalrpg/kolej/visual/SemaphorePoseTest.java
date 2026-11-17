// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.visual;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.signal.SemaphorePlacement;
import static org.junit.jupiter.api.Assertions.*;

class SemaphorePoseTest {
    @Test void stopPoseIsIdentityAndClearRotatesOnlyAroundActualHinge() {
        for(var pose:List.of(SemaphorePose.STOP,SemaphorePose.CLEAR))assertArrayEquals(new double[]{0,SemaphorePose.HINGE},pose.transform(0,SemaphorePose.HINGE),1e-6);
        assertArrayEquals(new double[]{34/16.0,77.5/16.0},SemaphorePose.STOP.transform(34/16.0,77.5/16.0),1e-8);
        var tip=SemaphorePose.CLEAR.transform(34/16.0,77.5/16.0);assertTrue(tip[1]>SemaphorePose.HINGE+1.4);assertTrue(tip[0]>1.4 && tip[0]<1.6);
    }
    @Test void existingDeterministicModelHasExpectedArmOriginIdAndHingeHeight() throws Exception {
        try(var in=getClass().getResourceAsStream("/infrastructure-models/kolej_semafor_mechaniczny.bbmodel")) {
            assertNotNull(in);var json=JsonParser.parseReader(new java.io.InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();JsonObject arm=null;
            for(var group:json.getAsJsonArray("groups"))if(group.getAsJsonObject().get("name").getAsString().equals("arm"))arm=group.getAsJsonObject();
            assertNotNull(arm);assertEquals("f0ea75d3-6143-5d06-8503-982f6280a834",arm.get("uuid").getAsString());
            for(var coordinate:arm.getAsJsonArray("origin"))assertEquals(0,coordinate.getAsDouble());
            for(var element:json.getAsJsonArray("elements"))if(element.getAsJsonObject().get("name").getAsString().equals("signal_arm")) {
                var cube=element.getAsJsonObject();assertEquals(SemaphorePose.HINGE,(cube.getAsJsonArray("from").get(1).getAsDouble()+cube.getAsJsonArray("to").get(1).getAsDouble())/32);
            }
        }
    }
    @Test void declaredSweptBoundsContainBothArmPosesAtAllRotations() {
        for(int quarter=0;quarter<4;quarter++) {
            var s=new SemaphorePlacement("s","n","edge",UUID.randomUUID().toString(),0,64,0,quarter,"STONE");s.validate();var b=s.bounds();
            for(var pose:List.of(SemaphorePose.STOP,SemaphorePose.CLEAR))for(double x:new double[]{0,34/16.0})for(double y:new double[]{75/16.0,80/16.0})for(double z:new double[]{-1.1/16,1.1/16}) {
                var p=pose.transform(x,y);double rx=switch(quarter){case 1 -> -z;case 2 -> -p[0];case 3 -> z;default -> p[0];};double rz=switch(quarter){case 1 -> p[0];case 2 -> -z;case 3 -> -p[0];default -> z;};
                assertTrue(b.contains(rx,rz,0));assertTrue(p[1]>=0 && p[1]<=b.height());
            }
        }
    }
}
