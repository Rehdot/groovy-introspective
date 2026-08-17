package me.redot.grin.agent;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/// A classloader that delegates loadClass calls to every distinct
/// ClassLoader instance currently live in the target JVM. Because it calls
/// loadClass() on those loaders rather than redefining their classes from
/// jar bytes, it preserves class identity with whatever the running
/// application is already using.
/// <p>
/// This is generally an awful approach to loading classes. However, in a
/// scripting environment where performance isn't the top priority, it's okay.
public class AggregateClassLoader extends ClassLoader {

    private final Instrumentation inst;
    private final List<ClassLoader> delegates = new ArrayList<>();
    private final Map<String, Class<?>> resolved = new ConcurrentHashMap<>();
    private final Set<ClassLoader> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ThreadLocal<Set<String>> processingClasses = ThreadLocal.withInitial(HashSet::new);
    private final ThreadLocal<Set<String>> processingResources = ThreadLocal.withInitial(HashSet::new);

    public AggregateClassLoader(ClassLoader parent, Instrumentation inst) {
        super(parent);
        this.inst = inst;
        this.refreshDelegates();
    }

    private synchronized void refreshDelegates() {
        Map<ClassLoader, Integer> counts = new IdentityHashMap<>();

        for (Class<?> clazz : this.inst.getAllLoadedClasses()) {
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

        Set<String> processing = this.processingClasses.get();
        if (!processing.add(name)) {
            throw new ClassNotFoundException(name + " (cyclic delegation)");
        }

        try {
            Class<?> result = this.resolveUncached(name);
            this.resolved.put(name, result);
            return result;
        } finally {
            processing.remove(name);
        }
    }

    private Class<?> resolveUncached(String name) throws ClassNotFoundException {
        try {
            return this.getParent().loadClass(name);
        } catch (ClassNotFoundException | LinkageError ignored) {}

        for (ClassLoader cl : this.delegates) {
            if (cl == null || cl == this) continue;
            try {
                return cl.loadClass(name);
            } catch (ClassNotFoundException | LinkageError ignored) {}
        }

        this.refreshDelegates();
        for (ClassLoader cl : this.delegates) {
            if (cl == null || cl == this) continue;
            try {
                return cl.loadClass(name);
            } catch (ClassNotFoundException | LinkageError ignored) {}
        }

        throw new ClassNotFoundException(name);
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