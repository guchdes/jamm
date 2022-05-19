package org.github.jamm;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

abstract class MemoryMeterBase extends MemoryMeter
{
    private static final String outerClassReference = "this\\$[0-9]+";

    private final ClassValue<ResolvedField[]> declaredClassFieldsCache = new ClassValue<ResolvedField[]>()
    {
        @Override
        protected ResolvedField[] computeValue(Class<?> type)
        {
            return declaredClassFields0(type);
        }
    };

    MemoryMeterBase(Builder builder)
    {
        super(builder);
    }

    @Override
    public long measure(Object obj)
    {
        Class<?> type = obj.getClass();

        if (type.isArray())
            return measureArray(obj, type);

        return measureNonArray(obj, type);
    }

    abstract long measureArray(Object obj, Class<?> type);

    abstract long measureNonArray(Object obj, Class<?> type);

    private boolean canAddChild(Object child) {
        return child != null
                && (visitChildPredicate == null || visitChildPredicate.test(child))
                && !ignoreClass.get(child.getClass());
    }

    /**
     * @return the memory usage of @param object including referenced objects
     * @throws NullPointerException if object is null
     */
    public final MeasureResult measureDeepWithDetail(Object object)
    {
        Objects.requireNonNull(object);
        long start = System.currentTimeMillis();
        if (ignoreClass.get(object.getClass()))
            return new MeasureResult(0, 0, 0);

        VisitedSet tracker = new VisitedSet(countLimit, timeLimitNanos);
        tracker.add(object);

        // track stack manually so we can handle deeper hierarchies than recursion
        Deque<Object> stack = new ArrayDeque<>();
        Map<Object, SourceTrackNode> refSourceMap = new IdentityHashMap<>();
        stack.push(object);

        long total = 0;
        Object current;
        SourceTrackNode currentRefSource = null;
        Class<?> type;
        long size;
        while (!stack.isEmpty())
        {
            current = stack.pop();
            if (debugStackTrace) {
                currentRefSource = refSourceMap.remove(current);
            }
            type = current.getClass();
            size = measure(current);
            total += size;

            if (type.isArray())
            {
                if (!type.getComponentType().isPrimitive())
                {
                    Object[] array = (Object[]) current;
                    for (int i = 0; i < array.length; i++)
                    {
                        Object child = array[i];
                        if (canAddChild(child) && tracker.add(child))
                        {
                            pushStack(stack, refSourceMap, currentRefSource, child, null, i);
                        }
                    }
                }
                continue;
            }

            if (byteBufferMode != BB_MODE_NORMAL && ByteBuffer.class.isAssignableFrom(type))
            {
                ByteBuffer bb = (ByteBuffer) current;
                if (byteBufferMode == BB_MODE_OMIT_SHARED)
                {
                    total += bb.remaining();
                    continue;
                }
                if (byteBufferMode == BB_MODE_SHALLOW)
                {
                    continue;
                }
                if (byteBufferMode == BB_MODE_HEAP_ONLY_NO_SLICE)
                {
                    if (bb.isDirect())
                        continue;
                    // if we're only referencing a sub-portion of the ByteBuffer, don't count the array overhead (assume it's slab
                    // allocated, so amortized over all the allocations the overhead is negligible and better to undercount than over)
                    if (bb.capacity() > bb.remaining())
                    {
                        total -= size;
                        total += bb.remaining();
                        continue;
                    }
                }
            }

            Object referent = (ignoreNonStrongReferences && (current instanceof Reference)) ? ((Reference<?>) current).get() : null;
            try
            {
                Class<?> cls = current.getClass();
                Object child;
                for (ResolvedField field : declaredClassFields(cls))
                {
                    child = field.methodHandle.invoke(current);

                    if (canAddChild(child) && child != referent && tracker.add(child))
                    {
                        pushStack(stack, refSourceMap, currentRefSource, child, field.name, 0);
                    }
                }
            }
            catch (Throwable t)
            {
                if (currentRefSource != null) {
                    throw new RuntimeException("Stack trace:" + currentRefSource.toString(), t);
                } else {
                    throw new RuntimeException(t);
                }
            }
        }

        return new MeasureResult(total, tracker.size, System.currentTimeMillis() - start);
    }

    private void pushStack(Deque<Object> stack, Map<Object, SourceTrackNode> refSourceMap,
                           SourceTrackNode parent, Object child,
                           String field, int arrayIndex)
    {
        stack.push(child);
        if (debugStackTrace)
        {
            SourceTrackNode sourceTrackNode = new SourceTrackNode(parent, child, field == null ? arrayIndex + "" : field);
            refSourceMap.put(child, sourceTrackNode);
        }
    }

    private static class SourceTrackNode {
        private final SourceTrackNode parent;
        private final Object object;
        private final String field;

        public SourceTrackNode(SourceTrackNode parent, Object object, String field) {
            this.parent = parent;
            this.object = object;
            this.field = field;
        }

        @Override
        public String toString() {
            List<String> list = new ArrayList<>();
            String fieldName = field;
            SourceTrackNode p = this;
            while (p != null) {
                String className = p.object.getClass().getName();
                if (fieldName != null) {
                    className += "." + fieldName;
                }
                list.add(className);
                fieldName = p.field;
                p = p.parent;
            }
            Collections.reverse(list);
            return String.join("->", list);
        }
    }

    // visible for testing
    static final class VisitedSet
    {
        final static int CHECK_TIME_ON_SIZE_INC = 10000;

        int size;
        final int objectLimit;
        final long startTimeNanos;
        final long timeLimitNanos;
        int lastCheckTimeSize;

        // Open-addressing table for this set.
        // This table will never be fully populated (1/3) to keep enough "spare slots" that are `null`
        // so a loop checking for an element would not have to check too many slots (iteration stops
        // when an entry in the table is `null`).
        Object[] table = new Object[16];

        public VisitedSet() {
            this(Integer.MAX_VALUE, Long.MAX_VALUE);
        }

        public VisitedSet(int limit, long timeLimitNanos) {
            this.objectLimit = limit;
            this.timeLimitNanos = timeLimitNanos;
            this.startTimeNanos = System.nanoTime();
        }

        boolean add(Object o)
        {
            // no need for a null-check here, see call-sites

            Object[] tab;
            Object item;
            int len, mask, i, s;
            for (; true; resize())
            {
                tab = table;
                len = tab.length;
                mask = len - 1;
                i = index(o, mask);

                while (true)
                {
                    item = tab[i];
                    if (item == null)
                        break;
                    if (item == o)
                        return false;
                    i = inc(i, len);
                }

                if (size >= objectLimit)
                {
                    throw new IllegalStateException("Object count limit reached!");
                }
                if (size - lastCheckTimeSize > CHECK_TIME_ON_SIZE_INC) {
                    if (System.nanoTime() - startTimeNanos > timeLimitNanos) {
                        throw new IllegalStateException("Object count time limit exceed!");
                    }
                    lastCheckTimeSize = size;
                }

                s = size + 1;
                // 3 as the "magic size factor" to have enough 'null's in the open-addressing-map
                if (s * 3 <= len)
                {
                    size = s;
                    tab[i] = o;
                    return true;
                }
            }
        }

        private void resize()
        {
            Object[] tab = table;

            int newLength = tab.length << 1;
            if (newLength < 0)
                throw new IllegalStateException("too many objects visited");

            Object[] n = new Object[newLength];
            int mask = newLength - 1;
            int i;
            for (Object o : tab)
            {
                if (o != null)
                {
                    i = index(o, mask);
                    while (n[i] != null)
                        i = inc(i, newLength);
                    n[i] = o;
                }
            }
            table = n;
        }

        private static int index(Object o, int mask)
        {
            return System.identityHashCode(o) & mask;
        }

        private static int inc(int i, int len)
        {
            int n = i + 1;
            return n >= len ? 0 : n;
        }
    }

    private ResolvedField[] declaredClassFields(Class<?> cls)
    {
        return declaredClassFieldsCache.get(cls);
    }

    @SuppressWarnings("deprecation")
    private ResolvedField[] declaredClassFields0(Class<?> cls)
    {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        List<ResolvedField> mhs = new ArrayList<>();
        for (; !skipClass(cls); cls = cls.getSuperclass())
        {
            for (Field f : cls.getDeclaredFields())
            {
                if (!f.getType().isPrimitive()
                    && !Modifier.isStatic(f.getModifiers())
                    && !f.isAnnotationPresent(Unmetered.class)
                    && !(ignoreOuterClassReference && f.getName().matches(outerClassReference))
                    && !ignoreClass.get(f.getType()))
                {
                    boolean acc = f.isAccessible();
                    try
                    {
                        if (!acc)
                            f.setAccessible(true);
                        mhs.add(new ResolvedField(f.getName(), lookup.unreflectGetter(f)));
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                    finally
                    {
                        if (!acc)
                            f.setAccessible(false);
                    }
               }
            }
        }
        return mhs.toArray(new ResolvedField[0]);
    }

    private static class ResolvedField {
        private final String name;
        private final MethodHandle methodHandle;

        public ResolvedField(String name, MethodHandle methodHandle) {
            this.name = name;
            this.methodHandle = methodHandle;
        }
    }
}
