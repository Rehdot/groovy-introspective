package me.redot.grin.agent;

import me.redot.grin.agent.completion.NamespaceClassIndex;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/// A classloader that exposes classes already loaded in the target JVM.
/// Resolution returns the exact class instances reported by Instrumentation.
/// <p>
/// This is generally an awful approach to loading classes. However, in a
/// scripting environment where performance isn't the top priority, it's okay.
public class AggregateClassLoader extends ClassLoader {

    private static volatile AggregateClassLoader instance;

    private static final long REFRESH_INTERVAL = 1000;

    private final Instrumentation inst;
    private final NamespaceClassIndex namespaceIndex;
    private final Map<String, Class<?>> resolved = new ConcurrentHashMap<>();
    private final ThreadLocal<Set<String>> processingResources = ThreadLocal.withInitial(HashSet::new);
    private volatile List<ClassLoader> delegates = Collections.emptyList();
    private volatile Map<String, Class<?>> loadedClasses = Collections.emptyMap();
    private volatile Map<ClassLoader, Integer> loaderClassCounts = Collections.emptyMap();
    private volatile Map<Path, List<LoaderScore>> sourceLoaders = Collections.emptyMap();
    private volatile long lastRefreshMS;

    public AggregateClassLoader(ClassLoader parent, Instrumentation inst, NamespaceClassIndex namespaceIndex) {
        super(parent);
        this.inst = inst;
        this.namespaceIndex = namespaceIndex;
        this.refreshDelegates();
    }

    public synchronized void refreshDelegates() {
        Class<?>[] classes = this.inst.getAllLoadedClasses();
        Map<ClassLoader, Integer> counts = new IdentityHashMap<>();
        Map<Path, Map<ClassLoader, Integer>> sourceCounts = new HashMap<>();
        Map<String, Path> locationPaths = new HashMap<>();
        Set<String> locationsWithoutPaths = new HashSet<>();

        for (Class<?> clazz : classes) {
            if (clazz == null) continue;

            ClassLoader cl = clazz.getClassLoader();
            if (cl == null || cl == this) continue;
            if (this.delegatesToSelf(cl)) continue;
            counts.merge(cl, 1, Integer::sum);

            Path source = sourcePath(clazz, locationPaths, locationsWithoutPaths);
            if (source != null) {
                sourceCounts.computeIfAbsent(source, ignored -> new IdentityHashMap<>())
                        .merge(cl, 1, Integer::sum);
            }
        }

        List<ClassLoader> fresh = new ArrayList<>(counts.keySet());
        fresh.sort(Comparator.<ClassLoader>comparingInt(counts::get).reversed());
        this.delegates = Collections.unmodifiableList(fresh);

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
        this.loaderClassCounts = Collections.unmodifiableMap(new IdentityHashMap<>(counts));

        Map<Path, List<LoaderScore>> owners = new HashMap<>();
        for (Map.Entry<Path, Map<ClassLoader, Integer>> entry : sourceCounts.entrySet()) {
            List<LoaderScore> scores = new ArrayList<>();
            for (Map.Entry<ClassLoader, Integer> owner : entry.getValue().entrySet()) {
                scores.add(new LoaderScore(owner.getKey(), owner.getValue(), counts.getOrDefault(owner.getKey(), 0)));
            }
            scores.sort(LoaderScore.ORDER);
            owners.put(entry.getKey(), Collections.unmodifiableList(scores));
        }
        this.sourceLoaders = Collections.unmodifiableMap(owners);
        this.lastRefreshMS = System.currentTimeMillis();
    }

    public static AggregateClassLoader getInstance() {
        return instance;
    }

    public static void setInstance(AggregateClassLoader loader) {
        instance = loader;
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
        if (result == null && this.canRefresh()) {
            this.refreshDelegates();
            result = this.loadedClasses.get(name);
        }

        if (result == null) {
            try {
                result = this.getParent().loadClass(name);
            } catch (ClassNotFoundException parentFailure) {
                result = this.loadFromIndexedSource(name);
                if (result == null) throw parentFailure;
            }
        }

        Class<?> existing = this.resolved.putIfAbsent(name, result);
        return existing != null ? existing : result;
    }

    public List<String> rankClassNames(Collection<String> names) {
        List<RankedClassName> ranked = new ArrayList<>();
        for (String name : names) ranked.add(this.rank(name));
        ranked.sort(RankedClassName.ORDER);

        List<String> result = new ArrayList<>(ranked.size());
        for (RankedClassName candidate : ranked) result.add(candidate.name);
        return result;
    }

    private RankedClassName rank(String name) {
        Class<?> loaded = this.loadedClasses.get(name);
        if (loaded != null) {
            ClassLoader loader = loaded.getClassLoader();
            int overallCount = this.loaderClassCounts.getOrDefault(loader, 0);
            int sourceCount = sourceLoaderCount(sourcePath(loaded), loader);
            return new RankedClassName(name, true, sourceCount, overallCount);
        }

        LoaderScore best = null;
        for (Path source : this.namespaceIndex.sourcesFor(name)) {
            List<LoaderScore> loaders = this.sourceLoaders.getOrDefault(source, Collections.emptyList());
            if (!loaders.isEmpty() && (best == null || LoaderScore.ORDER.compare(loaders.get(0), best) < 0)) {
                best = loaders.get(0);
            }
        }
        return best == null
                ? new RankedClassName(name, false, 0, 0)
                : new RankedClassName(name, false, best.sourceCount, best.overallCount);
    }

    private Class<?> loadFromIndexedSource(String name) {
        List<LoaderScore> candidates = new ArrayList<>();
        Set<ClassLoader> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Path source : this.namespaceIndex.sourcesFor(name)) {
            for (LoaderScore candidate : this.sourceLoaders.getOrDefault(source, Collections.emptyList())) {
                if (seen.add(candidate.loader)) candidates.add(candidate);
            }
        }
        candidates.sort(LoaderScore.ORDER);

        for (LoaderScore candidate : candidates) {
            try {
                return Class.forName(name, false, candidate.loader);
            } catch (ClassNotFoundException | LinkageError ignored) {}
        }
        return null;
    }

    private int sourceLoaderCount(Path source, ClassLoader loader) {
        if (source == null) return 0;
        for (LoaderScore score : this.sourceLoaders.getOrDefault(source, Collections.emptyList())) {
            if (score.loader == loader) return score.sourceCount;
        }
        return 0;
    }

    private static Path sourcePath(Class<?> clazz) {
        try {
            return sourcePath(clazz.getProtectionDomain());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Path sourcePath(
            Class<?> clazz,
            Map<String, Path> locationPaths,
            Set<String> locationsWithoutPaths) {
        try {
            URL location = getLocation(clazz.getProtectionDomain());
            if (isNotFileLocation(location)) return null;

            String key = location.toExternalForm();
            if (locationsWithoutPaths.contains(key)) return null;

            Path cached = locationPaths.get(key);
            if (cached != null) return cached;

            Path source;
            try {
                source = sourcePath(location);
            } catch (Exception ignored) {
                locationsWithoutPaths.add(key);
                return null;
            }

            locationPaths.put(key, source);
            return source;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isNotFileLocation(URL location) {
        return location == null || !"file".equalsIgnoreCase(location.getProtocol());
    }

    private static Path sourcePath(ProtectionDomain domain) throws Exception {
        URL location = getLocation(domain);
        if (isNotFileLocation(location)) return null;

        return sourcePath(location);
    }

    private static URL getLocation(ProtectionDomain domain) {
        if (domain == null) return null;

        CodeSource codeSource = domain.getCodeSource();
        if (codeSource == null) return null;

        return codeSource.getLocation();
    }

    private static Path sourcePath(URL location) throws Exception {
        return new File(location.toURI()).getCanonicalFile().toPath().toAbsolutePath().normalize();
    }

    private boolean canRefresh() {
        return System.currentTimeMillis() - this.lastRefreshMS >= REFRESH_INTERVAL;
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

    private static final class LoaderScore {
        private static final Comparator<LoaderScore> ORDER =
                Comparator.comparingInt(
                        (LoaderScore score) -> score.sourceCount
                ).reversed()
                .thenComparing(
                        Comparator.comparingInt((LoaderScore score) -> score.overallCount).reversed()
                );

        private final ClassLoader loader;
        private final int sourceCount;
        private final int overallCount;

        private LoaderScore(ClassLoader loader, int sourceCount, int overallCount) {
            this.loader = loader;
            this.sourceCount = sourceCount;
            this.overallCount = overallCount;
        }
    }

    private static final class RankedClassName {
        private static final Comparator<RankedClassName> ORDER =
                Comparator.comparing((RankedClassName candidate) -> candidate.loaded).reversed()
                        .thenComparing(Comparator.comparingInt((RankedClassName candidate) -> candidate.sourceCount).reversed())
                        .thenComparing(Comparator.comparingInt((RankedClassName candidate) -> candidate.overallCount).reversed())
                        .thenComparing(candidate -> candidate.name);

        private final String name;
        private final boolean loaded;
        private final int sourceCount;
        private final int overallCount;

        private RankedClassName(String name, boolean loaded, int sourceCount, int overallCount) {
            this.name = name;
            this.loaded = loaded;
            this.sourceCount = sourceCount;
            this.overallCount = overallCount;
        }
    }
}