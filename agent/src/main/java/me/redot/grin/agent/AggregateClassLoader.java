package me.redot.grin.agent;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/// A classloader that exposes classes already loaded in the target JVM.
/// Resolution returns the exact class instances reported by Instrumentation.
/// <p>
/// This is generally an awful approach to loading classes. However, in a
/// scripting environment where performance isn't the top priority, it's okay.
public class AggregateClassLoader extends ClassLoader {

    private final Instrumentation inst;
    private final List<ClassLoader> delegates = new ArrayList<>();
    private final Map<String, Class<?>> resolved = new ConcurrentHashMap<>();
    private final Set<ClassLoader> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ThreadLocal<Set<String>> processingResources = ThreadLocal.withInitial(HashSet::new);
    private volatile Map<String, Class<?>> loadedClasses = Collections.emptyMap();

    public AggregateClassLoader(ClassLoader parent, Instrumentation inst) {
        super(parent);
        this.inst = inst;
        this.refreshDelegates();
    }

    private synchronized void refreshDelegates() {
        Class<?>[] classes = this.inst.getAllLoadedClasses();
        Map<ClassLoader, Integer> counts = new IdentityHashMap<>();

        for (Class<?> clazz : classes) {
            ClassLoader cl = clazz.getClassLoader();
            if (cl == null || cl == this) continue;
            if (this.delegatesToSelf(cl)) continue;
            counts.merge(cl, 1, Integer::sum);
        }

        List<ClassLoader> fresh = new ArrayList<>(counts.keySet());
        fresh.sort(Comparator.<ClassLoader>comparingInt(counts::get).reversed());

        for (ClassLoader cl : fresh) {
            if (this.seen.add(cl)) {
                this.delegates.add(cl);
            }
        }

        Map<ClassLoader, Integer> rankedLoaders = new IdentityHashMap<>();
        rankedLoaders.put(null, -1);
        for (int i = 0; i < fresh.size(); i++) {
            rankedLoaders.put(fresh.get(i), i);
        }

        Map<String, Class<?>> loaded = new HashMap<>();
        for (Class<?> clazz : classes) {
            if (clazz == null) continue;

            loaded.merge(clazz.getName(), clazz, (current, candidate) -> {
                int currentRank = rankedLoaders.getOrDefault(current.getClassLoader(), Integer.MAX_VALUE);
                int candidateRank = rankedLoaders.getOrDefault(candidate.getClassLoader(), Integer.MAX_VALUE);
                return candidateRank < currentRank ? candidate : current;
            });
        }

        this.loadedClasses = Collections.unmodifiableMap(loaded);
    }

    private boolean delegatesToSelf(ClassLoader cl) {
        for (ClassLoader c = cl; c != null; c = c.getParent()) {
            if (c == this) return true;
        }
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (this.getClassLoadingLock(name)) {
            Class<?> clazz = this.findLoadedClass(name);

            if (clazz == null) {
                clazz = this.findClass(name);
            }

            if (resolve) {
                this.resolveClass(clazz);
            }

            return clazz;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        Class<?> cached = this.resolved.get(name);
        if (cached != null) return cached;

        Class<?> result = this.loadedClasses.get(name);
        if (result == null) {
            this.refreshDelegates();
            result = this.loadedClasses.get(name);
        }

        if (result == null) {
            result = this.getParent().loadClass(name);
        }

        Class<?> existing = this.resolved.putIfAbsent(name, result);
        return existing != null ? existing : result;
    }

    @Override
    protected URL findResource(String name) {
        Set<String> processing = this.processingResources.get();
        if (!processing.add(name)) {
            return null; // cyclic delegation
        }

        try {
            for (ClassLoader cl : this.delegates) {
                if (cl == null || cl == this) continue;
                URL resource = cl.getResource(name);
                if (resource != null) return resource;
            }
            return null;
        } finally {
            processing.remove(name);
        }
    }
}