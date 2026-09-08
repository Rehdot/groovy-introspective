package me.redot.grin.agent

import groovy.transform.CompileStatic

@CompileStatic
class GrinHeader {

    boolean visible = true
    private final Map<String, Closure<?>> fields = new LinkedHashMap<>()

    synchronized GrinHeader add(String label, Closure<?> supplier) {
        String normalized = normalizeLabel(label)
        if (supplier == null) {
            throw new IllegalArgumentException('Header supplier cannot be null')
        }
        fields.put(normalized, supplier)
        return this
    }

    GrinHeader add(String label, Object value) {
        return add(label, { value })
    }

    void putAt(String label, Closure<?> supplier) {
        add(label, supplier)
    }

    void putAt(String label, Object value) {
        add(label, value)
    }

    synchronized boolean remove(String label) {
        return fields.remove(label) != null
    }

    synchronized void clear() {
        fields.clear()
    }

    synchronized List<String> getNames() {
        return List.copyOf(fields.keySet())
    }

    Map<String, String> sample() {
        Map<String, Closure<?>> snapshot
        synchronized (this) {
            snapshot = new LinkedHashMap<>(fields)
        }

        Map<String, String> values = new LinkedHashMap<>()
        snapshot.each { String label, Closure<?> supplier ->
            try {
                Object value = supplier.call()
                values.put(label, value == null ? 'null' : String.valueOf(value))
            } catch (Throwable failure) {
                values.put(label, "<${failure.class.simpleName}>".toString())
            }
        }
        return values
    }

    private static String normalizeLabel(String label) {
        String normalized = label?.trim()
        if (!normalized || normalized.empty) {
            throw new IllegalArgumentException('Header label cannot be blank')
        }
        return normalized
    }

    @Override
    synchronized String toString() {
        return fields.isEmpty()
                ? 'header (no custom fields)'
                : "header (${fields.keySet().join(', ')})"
    }
}
