/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests für {@link MetricsCollector}.
 */
class MetricsCollectorTest {

    private MetricsCollector collector;

    @BeforeEach
    void setUp() {
        collector = new MetricsCollector();
    }

    @Test
    void incrementCounter_ErhoehtWert() {
        collector.incrementCounter("test_counter");
        assertEquals(1, collector.getCounter("test_counter"), 
                "Counter sollte 1 sein nach einem Increment");
        
        collector.incrementCounter("test_counter");
        assertEquals(2, collector.getCounter("test_counter"), 
                "Counter sollte 2 sein nach zwei Increments");
    }

    @Test
    void incrementCounter_MitDelta() {
        collector.incrementCounter("test_counter", 5);
        assertEquals(5, collector.getCounter("test_counter"), 
                "Counter sollte 5 sein nach Increment mit Delta 5");
        
        collector.incrementCounter("test_counter", 3);
        assertEquals(8, collector.getCounter("test_counter"), 
                "Counter sollte 8 sein nach Increment mit Delta 3");
    }

    @Test
    void incrementCounter_NeuerCounter_InitialisiertMitNull() {
        assertEquals(0, collector.getCounter("non_existent"), 
                "Nicht existenter Counter sollte 0 sein");
    }

    @Test
    void recordDuration_ZeichnetWerteAuf() {
        collector.recordDuration("test_duration", 100);
        collector.recordDuration("test_duration", 200);
        
        assertEquals(150.0, collector.getAverageDuration("test_duration"), 0.001,
                "Durchschnitt sollte (100+200)/2 = 150 sein");
    }

    @Test
    void recordDuration_NeueMetrik_RueckgabeNull() {
        assertEquals(0.0, collector.getAverageDuration("non_existent"), 0.001,
                "Nicht existente Duration sollte 0.0 sein");
    }

    @Test
    void snapshotCounters_GibtAlleCounterZurueck() {
        collector.incrementCounter("counter1", 1);
        collector.incrementCounter("counter2", 2);
        
        Map<String, Long> snapshot = collector.snapshotCounters();
        assertNotNull(snapshot, "Snapshot sollte nicht null sein");
        assertEquals(2, snapshot.size(), "Snapshot sollte 2 Counter enthalten");
        assertEquals(1L, snapshot.get("counter1"), "Counter1 sollte 1 sein");
        assertEquals(2L, snapshot.get("counter2"), "Counter2 sollte 2 sein");
    }

    @Test
    void snapshotDurations_GibtAlleDurationsZurueck() {
        collector.recordDuration("duration1", 100);
        collector.recordDuration("duration2", 200);
        collector.recordDuration("duration2", 300);
        
        Map<String, Double> snapshot = collector.snapshotDurations();
        assertNotNull(snapshot, "Snapshot sollte nicht null sein");
        assertEquals(2, snapshot.size(), "Snapshot sollte 2 Durations enthalten");
        assertEquals(100.0, snapshot.get("duration1"), 0.001, "Duration1 sollte 100 sein");
        assertEquals(250.0, snapshot.get("duration2"), 0.001, "Duration2 sollte 250 sein");
    }

    @Test
    void reset_LoeschtAlleMetriken() {
        collector.incrementCounter("counter1", 10);
        collector.recordDuration("duration1", 100);
        
        collector.reset();
        
        assertEquals(0, collector.getCounter("counter1"), "Counter sollte 0 sein nach Reset");
        assertEquals(0.0, collector.getAverageDuration("duration1"), 0.001, 
                "Duration sollte 0 sein nach Reset");
    }

    @Test
    void multipleCounters_Unabhaengig() {
        collector.incrementCounter("counter1", 1);
        collector.incrementCounter("counter2", 2);
        
        assertEquals(1, collector.getCounter("counter1"));
        assertEquals(2, collector.getCounter("counter2"));
    }

    @Test
    void duration_AverageBerechnung() {
        collector.recordDuration("test", 10);
        collector.recordDuration("test", 20);
        collector.recordDuration("test", 30);
        
        assertEquals(20.0, collector.getAverageDuration("test"), 0.001,
                "Durchschnitt sollte (10+20+30)/3 = 20 sein");
    }

    @Test
    void duration_EinzelnerWert() {
        collector.recordDuration("single", 42);
        assertEquals(42.0, collector.getAverageDuration("single"), 0.001,
                "Einzelner Wert sollte 42 sein");
    }
}
