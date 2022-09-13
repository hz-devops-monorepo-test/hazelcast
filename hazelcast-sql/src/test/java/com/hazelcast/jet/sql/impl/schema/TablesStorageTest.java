/*
 * Copyright 2021 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.sql.impl.schema;

import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.EntryListener;
import com.hazelcast.jet.SimpleTestInClusterSupport;
import com.hazelcast.map.MapEvent;
import com.hazelcast.sql.impl.schema.Mapping;
import com.hazelcast.sql.impl.schema.view.View;
import com.hazelcast.test.Accessors;
import com.hazelcast.test.annotation.ParallelJVMTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@Category({QuickTest.class, ParallelJVMTest.class})
public class TablesStorageTest extends SimpleTestInClusterSupport {
    private TablesStorage storage;

    @BeforeClass
    public static void setUpClass() {
        initialize(1, null);
    }

    @Before
    public void before() {
        storage = new TablesStorage(Accessors.getNodeEngineImpl(instance()));
    }

    @Test
    public void when_put_then_isPresentInValues() {
        String name = randomName();

        storage.put(name, mapping(name, "type"));

        assertThat(storage.mappingNames().stream().filter(m -> m.equals(name))).isNotEmpty();
    }

    @Test
    public void when_put_then_overridesPrevious() {
        String name = randomName();
        Mapping originalMapping = mapping(name, "type1");
        Mapping updatedMapping = mapping(name, "type2");

        storage.put(name, originalMapping);
        storage.put(name, updatedMapping);

        assertTrue(storage.allObjects().stream().noneMatch(m -> m.equals(originalMapping)));
        assertTrue(storage.allObjects().stream().anyMatch(m -> m.equals(updatedMapping)));
    }

    @Test
    public void when_putIfAbsent_then_doesNotOverride() {
        String name = randomName();

        assertThat(storage.putIfAbsent(name, mapping(name, "type-1"))).isTrue();
        assertThat(storage.putIfAbsent(name, mapping(name, "type-2"))).isFalse();
        assertTrue(storage.allObjects().stream().anyMatch(m -> m instanceof Mapping && ((Mapping) m).type().equals("type-1")));
        assertTrue(storage.allObjects().stream().noneMatch(m -> m instanceof Mapping && ((Mapping) m).type().equals("type-2")));
    }

    @Test
    public void when_removeMapping_then_isNotPresentInValues() {
        String name = randomName();

        storage.put(name, mapping(name, "type"));

        assertThat(storage.removeMapping(name)).isNotNull();
        assertTrue(storage.mappingNames().stream().noneMatch(m -> m.equals(name)));
    }

    @Test
    public void when_removeView_then_isNotPresentInValues() {
        String name = randomName();

        storage.put(name, view(name, "type"));

        assertThat(storage.removeView(name)).isNotNull();
        assertTrue(storage.allObjects().stream().noneMatch(o -> o instanceof View && ((View) o).name().equals(name)));
    }

    @Test
    public void when_removeAbsentValue_then_returnsNull() {
        assertThat(storage.removeView("non-existing")).isNull();
    }

    @Test
    public void when_clusterVersionIs5dot2_then_onlyNewCatalogIsUsed() {
        String name = randomName();
        storage.put(name, mapping(name, "type"));

        assertEquals(1, storage.newStorage().size());
        assertEquals(0, storage.oldStorage().size());
    }

    @Test
    public void when_clusterVersionIs5dot2_then_oldCatalogIsMigratedOnFirstReadBeforeInitialization() {
        String name = randomName();
        storage.oldStorage().put(name, mapping(name, "type"));

        assertEquals(1, storage.allObjects().size());
        assertEquals(1, storage.newStorage().size());
        assertEquals(0, storage.oldStorage().size());
    }

    @Test
    public void when_clusterVersionIs5dot2_then_oldCatalogIsNotMigratedOnFirstReadAfterInitialization() {
        storage.initializeWithListener(new TablesStorage.EntryListenerAdapter() {
            @Override
            public void entryUpdated(EntryEvent<String, Object> event) {
            }

            @Override
            public void entryRemoved(EntryEvent<String, Object> event) {
            }
        });
        String name = randomName();
        storage.oldStorage().put(name, mapping(name, "type"));

        assertEquals(0, storage.allObjects().size());
        assertEquals(1, storage.oldStorage().size());
    }

    @Test
    public void when_clusterVersionIs5dot2_then_listenerIsAppliedOnNewCatalogOnly() throws InterruptedException {
        AtomicInteger clearCounter = new AtomicInteger();
        storage.initializeWithListener(getCountingOnClearEntryListener(clearCounter));

        String name = randomName();
        storage.newStorage().put(name, mapping(name, "type"));
        storage.oldStorage().put(name, mapping(name, "type"));
        storage.oldStorage().clear();
        storage.newStorage().clear();

        assertTrueEventually(() -> {
            assertEquals(1, clearCounter.get());
        });
        MILLISECONDS.sleep(100);
        assertEquals(1, clearCounter.get());
    }

    private static Mapping mapping(String name, String type) {
        return new Mapping(name, name, type, emptyList(), emptyMap());
    }

    private static View view(String name, String query) {
        return new View(name, query, emptyList(), emptyList());
    }

    private EntryListener<String, Object> getCountingOnClearEntryListener(AtomicInteger clearCounter) {
        return new EntryListener<String, Object>() {
            @Override
            public void mapCleared(MapEvent event) {
                clearCounter.incrementAndGet();
            }

            @Override
            public void entryAdded(EntryEvent<String, Object> event) { }

            @Override
            public void entryEvicted(EntryEvent<String, Object> event) { }

            @Override
            public void entryExpired(EntryEvent<String, Object> event) { }

            @Override
            public void entryRemoved(EntryEvent<String, Object> event) { }

            @Override
            public void entryUpdated(EntryEvent<String, Object> event) { }

            @Override
            public void mapEvicted(MapEvent event) { }
        };
    }

    private static class EmptyEntryListener implements EntryListener<String, Object> {
        @Override
        public void entryAdded(EntryEvent<String, Object> event) { }

        @Override
        public void entryRemoved(EntryEvent<String, Object> event) { }

        @Override
        public void entryUpdated(EntryEvent<String, Object> event) { }

        @Override
        public void entryEvicted(EntryEvent<String, Object> event) { }

        @Override
        public void mapEvicted(MapEvent event) { }

        @Override
        public void mapCleared(MapEvent event) { }

        @Override
        public void entryExpired(EntryEvent<String, Object> event) { }
    }
}
