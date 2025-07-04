/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.utils;

import com.google.common.base.Objects;

import static com.google.common.base.Preconditions.checkNotNull;

public class Interval<C, D>
{
    public final C min;
    public final C max;
    public final D data;

    public Interval(C min, C max, D data)
    {
        checkNotNull(min, "min is null");
        checkNotNull(max, "max is null");
        this.min = min;
        this.max = max;
        this.data = data;
    }

    public static <C, D> Interval<C, D> create(C min, C max)
    {
        return create(min, max, null);
    }

    public static <C, D> Interval<C, D> create(C min, C max, D data)
    {
        return new Interval(min, max, data);
    }

    @Override
    public String toString()
    {
        return String.format("[%s, %s]%s", min, max, data == null ? "" : (String.format("(%s)", data)));
    }

    @Override
    public final int hashCode()
    {
        return Objects.hashCode(min, max, data);
    }

    @Override
    public final boolean equals(Object o)
    {
        if(!(o instanceof Interval))
            return false;

        Interval that = (Interval)o;
        // handles nulls properly
        return Objects.equal(min, that.min) && Objects.equal(max, that.max) && Objects.equal(data, that.data);
    }

    private static final AsymmetricOrdering<Interval<Comparable, Comparable>, Comparable> minOrdering
    = new AsymmetricOrdering<Interval<Comparable, Comparable>, Comparable>()
    {
        public int compareAsymmetric(Interval<Comparable, Comparable> left, Comparable right)
        {
            return left.min.compareTo(right);
        }

        public int compare(Interval<Comparable, Comparable> i1, Interval<Comparable, Comparable> i2)
        {
            int cmpMin = i1.min.compareTo(i2.min);
            if (cmpMin != 0)
                return cmpMin;
            int cmpMax = i1.max.compareTo(i2.max);
            if (cmpMax != 0)
                return cmpMax;
            // Null is allowed if all data values are null otherwise NPE
            return i1.data == i2.data ? 0 : i1.data.compareTo(i2.data);
        }
    };

    private static final AsymmetricOrdering<Interval<Comparable, Comparable>, Comparable> maxOrdering
    = new AsymmetricOrdering<Interval<Comparable, Comparable>, Comparable>()
    {
        public int compareAsymmetric(Interval<Comparable, Comparable> left, Comparable right)
        {
            return left.max.compareTo(right);
        }

        public int compare(Interval<Comparable, Comparable> i1, Interval<Comparable, Comparable> i2)
        {
            int cmpMax = i1.max.compareTo(i2.max);
            if (cmpMax != 0)
                return cmpMax;
            int cmpMin = i1.min.compareTo(i2.min);
            if (cmpMin != 0)
                return cmpMin;
            // Null is allowed if all data values are null otherwise NPE
            return i1.data == i2.data ? 0 : i1.data.compareTo(i2.data);
        }
    };

    private static final AsymmetricOrdering<Interval<Comparable, Comparable>, Comparable> reverseMaxOrdering = maxOrdering.reverse();

    public static <C extends Comparable<? super C>, V> AsymmetricOrdering<Interval<C, V>, C> minOrdering()
    {
        return (AsymmetricOrdering) minOrdering;
    }

    public static <C extends Comparable<? super C>, V> AsymmetricOrdering<Interval<C, V>, C> maxOrdering()
    {
        return (AsymmetricOrdering) maxOrdering;
    }
}
