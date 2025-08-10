package com.lowdragmc.photon.client.gameobject.particle.aratrail;

import java.lang.reflect.Array;
import java.util.*;

/**
 * Custom List implementation that allows access to the underlying raw array. This enables several tricks
 * that aren't possible with ArrayList<T>, such as passing a reference to the contents of an entry to a function.
 */
public class ElasticArray<T> implements List<T> {
    private final Class<T> clazz;
    private T[] data;
    private int count = 0;

    @SuppressWarnings("unchecked")
    public ElasticArray(Class<T> clazz) {
        this.clazz = clazz;
        this.data = (T[]) Array.newInstance(clazz, 16);
    }

    // Implementation of List<T>

    @Override
    public int size() {
        return count;
    }

    @Override
    public boolean isEmpty() {
        return count == 0;
    }

    @Override
    public boolean contains(Object o) {
        for (int i = 0; i < count; ++i) {
            if (Objects.equals(data[i], o)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<T>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < count;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return data[index++];
            }
        };
    }

    @Override
    public T[] toArray() {
        return Arrays.copyOf(data, count);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <U> U[] toArray(U[] a) {
        if (a.length < count) {
            return (U[]) Arrays.copyOf(data, count, a.getClass());
        }
        System.arraycopy(data, 0, a, 0, count);
        if (a.length > count) {
            a[count] = null;
        }
        return a;
    }

    @Override
    public boolean add(T item) {
        ensureCapacity(count + 1);
        data[count++] = item;
        return true;
    }

    @Override
    public boolean remove(Object o) {
        boolean found = false;
        for (int i = 0; i < count; ++i) {
            // Look for the element, and mark it as found.
            if (!found && Objects.equals(data[i], o)) {
                found = true;
            }

            // If we found the element and are not at the last element, displace the element 1 position backwards.
            if (found && i < count - 1) {
                data[i] = data[i + 1];
            }
        }

        // If we found and removed the element, reduce the element count by 1.
        if (found) {
            count--;
        }

        return found;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object item : c) {
            if (!contains(item)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        boolean modified = false;
        for (T item : c) {
            if (add(item)) {
                modified = true;
            }
        }
        return modified;
    }

    @Override
    public boolean addAll(int index, Collection<? extends T> c) {
        if (index < 0 || index > count) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + count);
        }
        
        int cSize = c.size();
        if (cSize == 0) {
            return false;
        }

        ensureCapacity(count + cSize);
        
        // Move existing elements to make room
        System.arraycopy(data, index, data, index + cSize, count - index);
        
        // Insert new elements
        int i = index;
        for (T item : c) {
            data[i++] = item;
        }
        
        count += cSize;
        return true;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        boolean modified = false;
        for (Object item : c) {
            while (remove(item)) {
                modified = true;
            }
        }
        return modified;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        boolean modified = false;
        for (int i = count - 1; i >= 0; i--) {
            if (!c.contains(data[i])) {
                removeAt(i);
                modified = true;
            }
        }
        return modified;
    }

    @Override
    public void clear() {
        count = 0;
    }

    @Override
    public T get(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + count);
        }
        return data[index];
    }

    @Override
    public T set(int index, T element) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + count);
        }
        T oldValue = data[index];
        data[index] = element;
        return oldValue;
    }

    @Override
    public void add(int index, T element) {
        if (index < 0 || index > count) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + count);
        }

        ensureCapacity(++count);

        for (int i = count - 1; i > index; --i) {
            data[i] = data[i - 1];
        }

        data[index] = element;
    }

    @Override
    public T remove(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + count);
        }

        T oldValue = data[index];
        removeAt(index);
        return oldValue;
    }

    @Override
    public int indexOf(Object o) {
        for (int i = 0; i < count; i++) {
            if (Objects.equals(data[i], o)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int lastIndexOf(Object o) {
        for (int i = count - 1; i >= 0; i--) {
            if (Objects.equals(data[i], o)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public ListIterator<T> listIterator() {
        return listIterator(0);
    }

    @Override
    public ListIterator<T> listIterator(int index) {
        if (index < 0 || index > count) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + count);
        }
        return new ListIterator<T>() {
            private int cursor = index;

            @Override
            public boolean hasNext() {
                return cursor < count;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return data[cursor++];
            }

            @Override
            public boolean hasPrevious() {
                return cursor > 0;
            }

            @Override
            public T previous() {
                if (!hasPrevious()) {
                    throw new NoSuchElementException();
                }
                return data[--cursor];
            }

            @Override
            public int nextIndex() {
                return cursor;
            }

            @Override
            public int previousIndex() {
                return cursor - 1;
            }

            @Override
            public void remove() {
                if (cursor <= 0) {
                    throw new IllegalStateException();
                }
                ElasticArray.this.remove(--cursor);
            }

            @Override
            public void set(T t) {
                if (cursor <= 0 || cursor > count) {
                    throw new IllegalStateException();
                }
                data[cursor - 1] = t;
            }

            @Override
            public void add(T t) {
                ElasticArray.this.add(cursor++, t);
            }
        };
    }

    @Override
    public List<T> subList(int fromIndex, int toIndex) {
        if (fromIndex < 0 || toIndex > count || fromIndex > toIndex) {
            throw new IndexOutOfBoundsException("fromIndex: " + fromIndex + ", toIndex: " + toIndex + ", size: " + count);
        }
        List<T> result = new ArrayList<>(toIndex - fromIndex);
        for (int i = fromIndex; i < toIndex; i++) {
            result.add(data[i]);
        }
        return result;
    }

    // Additional methods from original ElasticArray

    /**
     * Get access to the underlying raw array
     */
    public T[] getData() {
        return data;
    }

    /**
     * Ensures a minimal capacity of count elements, then sets the new count. Useful when passing the backing array to C++
     * for being filled with new data.
     */
    public void setCount(int count) {
        ensureCapacity(count);
        this.count = count;
    }

    @SuppressWarnings("unchecked")
    public void ensureCapacity(int capacity) {
        if (capacity >= data.length) {
            T[] newData = (T[]) Array.newInstance(clazz, capacity * 2);
            System.arraycopy(data, 0, newData, 0, count);
            data = newData;
        }
    }

    public void removeAt(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + count);
        }
        
        for (int i = index; i < count - 1; ++i) {
            data[i] = data[i + 1];
        }
        data[--count] = null; // Clear reference
    }

    public void removeRange(int index, int num) {
        if (index < 0 || num < 0) {
            throw new IndexOutOfBoundsException("Index: " + index + ", num: " + num);
        }
        if (index + num > count) {
            throw new IndexOutOfBoundsException("Index + num: " + (index + num) + ", size: " + count);
        }

        for (int i = index + num - 1; i >= index; --i) {
            removeAt(i);
        }
    }

    public void reverse() {
        int i = 0;
        int j = count - 1;
        while (i < j) {
            T temp = data[i];
            data[i++] = data[j];
            data[j--] = temp;
        }
    }
}
