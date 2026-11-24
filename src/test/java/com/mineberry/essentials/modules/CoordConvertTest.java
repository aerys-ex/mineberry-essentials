package com.mineberry.essentials.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mineberry.essentials.modules.CoordConvert.Dim;
import com.mineberry.essentials.modules.CoordConvert.Request;
import com.mineberry.essentials.modules.CoordConvert.Result;
import org.junit.jupiter.api.Test;

class CoordConvertTest {

    private static Request req(String message) {
        return CoordConvert.parse(message).orElseThrow(() -> new AssertionError("not a conversion: " + message));
    }

    @Test void overworldToNetherDividesByEight() {
        Request r = req("convert 800 64 -1600 to nether");
        assertEquals(new Request(Dim.NETHER, 800, 64, -1600, false, false), r);
        Result res = CoordConvert.convert(r, Dim.OVERWORLD, 0, 0, 0);
        assertEquals(100, res.toX());
        assertEquals(-200, res.toZ());
        assertEquals("Overworld 800, 64, -1600 → Nether 100, 64, -200", res.text());
    }

    @Test void negativeNumbersRoundDown() {
        Result res = CoordConvert.convert(req("convert -1579 63 2782 to the nether"), Dim.OVERWORLD, 0, 0, 0);
        assertEquals(-198, res.toX());
        assertEquals(347, res.toZ());
    }

    @Test void netherToOverworldMultipliesByEight() {
        Result res = CoordConvert.convert(req("convert 100 200 to overworld"), Dim.NETHER, 0, 0, 0);
        assertEquals(800, res.toX());
        assertEquals(1600, res.toZ());
        assertEquals(Dim.NETHER, res.from());
        assertEquals("Nether 100, 200 → Overworld 800, 1600", res.text());
    }

    @Test void fromMeansTheOtherWay() {
        assertEquals(Dim.OVERWORLD, req("convert 100 200 from nether").to());
        assertEquals(Dim.NETHER, req("convert 100 200 from the overworld").to());
        assertEquals(Dim.NETHER, req("convert overworld 100 200 to nether").to());
    }

    @Test void looseWording() {
        Request r = req("nether coords for x: 1000 z: -400");
        assertEquals(new Request(Dim.NETHER, 1000, null, -400, false, false), r);
        assertEquals(125, CoordConvert.convert(r, Dim.OVERWORLD, 0, 0, 0).toX());
        assertEquals(-50, CoordConvert.convert(r, Dim.OVERWORLD, 0, 0, 0).toZ());
        assertEquals(Dim.OVERWORLD, req("overworld coordinates of 80, -64").to());
    }

    @Test void noDimensionNamedUsesTheOneYouAreIn() {
        Request r = req("convert coords 800 -1600");
        assertEquals(null, r.to());
        assertEquals(Dim.NETHER, CoordConvert.convert(r, Dim.OVERWORLD, 0, 0, 0).to());
        assertEquals(Dim.OVERWORLD, CoordConvert.convert(r, Dim.NETHER, 0, 0, 0).to());
    }

    @Test void myCoordsUseThePlayerPosition() {
        Request r = req("convert my coords to the nether");
        assertTrue(r.useMine());
        Result res = CoordConvert.convert(r, Dim.OVERWORLD, 160, 70, -80);
        assertEquals(20, res.toX());
        assertEquals(-10, res.toZ());
        assertEquals(Integer.valueOf(70), res.y());
    }

    @Test void askingToAddItToTheWaypoints() {
        assertTrue(req("convert 800 64 -1600 to nether and add it to waypoints").addWaypoint());
        assertTrue(req("convert my coords to nether and save a waypoint").addWaypoint());
        assertFalse(req("convert 800 64 -1600 to nether").addWaypoint());
    }

    @Test void addingThePreviousResult() {
        assertTrue(CoordConvert.wantsAddLast("add that to waypoints"));
        assertTrue(CoordConvert.wantsAddLast("please save those coords as a waypoint"));
        assertFalse(CoordConvert.wantsAddLast("add the airdrop to waypoints"));
        assertFalse(CoordConvert.wantsAddLast("add waypoint 100 64 200"));
        assertFalse(CoordConvert.wantsAddLast("remove that waypoint"));
        assertFalse(CoordConvert.wantsAddLast("hello"));
    }

    @Test void otherTalkIsNotAConversion() {
        assertTrue(CoordConvert.parse("how do nether portals work").isEmpty());
        assertTrue(CoordConvert.parse("convert 5 + 3").isEmpty());
        assertTrue(CoordConvert.parse("what is rule 5.7").isEmpty());
        assertTrue(CoordConvert.parse("convert 800 to nether").isEmpty());     // one number is not a position
        assertTrue(CoordConvert.parse("convert airdrop coords to nether 1 2").isEmpty());
        assertTrue(CoordConvert.parse(null).isEmpty());
    }

    @Test void theDimensionOfAnId() {
        assertEquals(Dim.NETHER, Dim.of("minecraft:the_nether"));
        assertEquals(Dim.OVERWORLD, Dim.of("minecraft:overworld"));
        assertEquals(Dim.OVERWORLD, Dim.of("unknown"));
    }
}
