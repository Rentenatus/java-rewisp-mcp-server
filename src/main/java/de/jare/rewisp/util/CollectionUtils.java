/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import java.util.*;

/**
 * Utility methods for collection operations.
 *
 * <p>Centralizes common collection patterns: creating unmodifiable collections,
 * copying collections, and adding elements to copies.
 */
public final class CollectionUtils {

    private CollectionUtils() {}

    // ==================== UNMODIFIABLE CREATION ====================

    /**
     * Creates an unmodifiable set from the given elements.
     *
     * @param elements the elements to include in the set
     * @param <T> the element type
     * @return an unmodifiable set containing the elements
     */
    @SafeVarargs
    public static <T> Set<T> unmodifiableSet(T... elements) {
        return elements != null ? Set.of(elements) : Set.of();
    }

    /**
     * Creates an unmodifiable set from a collection.
     *
     * @param collection the collection to convert, may be null
     * @param <T> the element type
     * @return an unmodifiable set, or empty set if input was null
     */
    public static <T> Set<T> unmodifiableSet(Collection<T> collection) {
        if (collection == null || collection.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new HashSet<>(collection));
    }

    /**
     * Creates an unmodifiable map from a map.
     *
     * @param map the map to convert, may be null
     * @param <K> the key type
     * @param <V> the value type
     * @return an unmodifiable map, or empty map if input was null
     */
    public static <K, V> Map<K, V> unmodifiableMap(Map<K, V> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new HashMap<>(map));
    }

    /**
     * Creates an unmodifiable list from a collection.
     *
     * @param collection the collection to convert, may be null
     * @param <T> the element type
     * @return an unmodifiable list, or empty list if input was null
     */
    public static <T> List<T> unmodifiableList(Collection<T> collection) {
        if (collection == null || collection.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(collection));
    }

    // ==================== COPY OPERATIONS ====================

    /**
     * Creates a mutable copy of a collection.
     *
     * @param collection the collection to copy, may be null
     * @param <T> the element type
     * @return a new mutable set, or empty set if input was null
     */
    public static <T> Set<T> copyOf(Collection<T> collection) {
        if (collection == null || collection.isEmpty()) {
            return new HashSet<>();
        }
        return new HashSet<>(collection);
    }

    /**
     * Creates a mutable copy of a map.
     *
     * @param map the map to copy, may be null
     * @param <K> the key type
     * @param <V> the value type
     * @return a new mutable map, or empty map if input was null
     */
    public static <K, V> Map<K, V> copyOfMap(Map<K, V> map) {
        if (map == null || map.isEmpty()) {
            return new HashMap<>();
        }
        return new HashMap<>(map);
    }

    // ==================== ADD TO COPY PATTERNS ====================

    /**
     * Creates a copy of the set and adds the specified element.
     *
     * @param set the set to copy, may be null
     * @param element the element to add, may be null
     * @param <T> the element type
     * @return an unmodifiable set containing all elements from the original plus the new element
     */
    public static <T> Set<T> addToCopy(Set<T> set, T element) {
        Set<T> copy = copyOf(set);
        if (element != null) {
            copy.add(element);
        }
        return unmodifiableSet(copy);
    }

    /**
     * Creates a copy of the set and adds all specified elements.
     *
     * @param set the set to copy, may be null
     * @param elements the elements to add, may be null
     * @param <T> the element type
     * @return an unmodifiable set containing all elements from the original plus the new elements
     */
    public static <T> Set<T> addAllToCopy(Set<T> set, Collection<T> elements) {
        Set<T> copy = copyOf(set);
        if (elements != null) {
            copy.addAll(elements);
        }
        return unmodifiableSet(copy);
    }

    /**
     * Creates a copy of the set and adds the specified element if it's not null.
     *
     * @param set the set to copy, may be null
     * @param element the element to add
     * @param <T> the element type
     * @return an unmodifiable set containing all elements from the original plus the new element
     */
    public static <T> Set<T> addNonNullToCopy(Set<T> set, T element) {
        if (element == null) {
            return unmodifiableSet(set);
        }
        return addToCopy(set, element);
    }

    // ==================== MERGE OPERATIONS ====================

    /**
     * Merges two sets into a new unmodifiable set.
     *
     * @param first the first set, may be null
     * @param second the second set, may be null
     * @param <T> the element type
     * @return an unmodifiable set containing all elements from both sets
     */
    public static <T> Set<T> mergeSets(Set<T> first, Set<T> second) {
        Set<T> result = new HashSet<>();
        if (first != null) result.addAll(first);
        if (second != null) result.addAll(second);
        return unmodifiableSet(result);
    }

    /**
     * Merges a set with additional elements into a new unmodifiable set.
     *
     * @param set the base set, may be null
     * @param additional the additional elements, may be null
     * @param <T> the element type
     * @return an unmodifiable set containing all elements
     */
    public static <T> Set<T> mergeWithElements(Set<T> set, Collection<T> additional) {
        Set<T> result = copyOf(set);
        if (additional != null) {
            result.addAll(additional);
        }
        return unmodifiableSet(result);
    }

    // ==================== NULL-SAFE OPERATIONS ====================

    /**
     * Returns the size of a collection, or 0 if null.
     *
     * @param collection the collection, may be null
     * @return the size, or 0
     */
    public static int size(Collection<?> collection) {
        return collection != null ? collection.size() : 0;
    }

    /**
     * Checks if a collection is null or empty.
     *
     * @param collection the collection, may be null
     * @return true if null or empty
     */
    public static boolean isNullOrEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    /**
     * Checks if a map is null or empty.
     *
     * @param map the map, may be null
     * @return true if null or empty
     */
    public static boolean isNullOrEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    /**
     * Returns the first element of a collection, or null if empty or null.
     *
     * @param collection the collection
     * @param <T> the element type
     * @return the first element, or null
     */
    public static <T> T firstOrNull(Collection<T> collection) {
        if (isNullOrEmpty(collection)) return null;
        return collection.iterator().next();
    }

    // ==================== BUILDER PATTERNS ====================

    /**
     * Builder for creating unmodifiable sets with a fluent API.
     *
     * @param <T> the element type
     */
    public static class SetBuilder<T> {
        private final Set<T> elements = new LinkedHashSet<>();

        public SetBuilder<T> add(T element) {
            if (element != null) {
                elements.add(element);
            }
            return this;
        }

        public SetBuilder<T> addAll(Collection<T> collection) {
            if (collection != null) {
                elements.addAll(collection);
            }
            return this;
        }

        public Set<T> build() {
            return unmodifiableSet(elements);
        }

        public Set<T> buildCopy() {
            return new HashSet<>(elements);
        }
    }

    /**
     * Creates a new SetBuilder.
     *
     * @param <T> the element type
     * @return a new SetBuilder instance
     */
    public static <T> SetBuilder<T> setBuilder() {
        return new SetBuilder<>();
    }
}
