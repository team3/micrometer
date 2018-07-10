/**
 * Copyright 2017 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.core.instrument.config;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.lang.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.stream.StreamSupport.stream;

/**
 * As requests are made of a {@link MeterRegistry} to create new metrics, allow for filtering out
 * the metric altogether, transforming its ID (name or tags) in some way, and transforming its
 * configuration.
 * <p>
 * All new metrics should pass through each {@link MeterFilter} in the order in which they were added.
 *
 * @author Jon Schneider
 * @author Johnny Lim
 */
public interface MeterFilter {
    /**
     * Add common tags that are applied to every meter created afterward.
     *
     * @param tags Common tags.
     * @return A common tag filter.
     */
    static MeterFilter commonTags(Iterable<Tag> tags) {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                List<Tag> allTags = new ArrayList<>(id.getTags());
                tags.forEach(allTags::add);
                return new Meter.Id(id.getName(), allTags, id.getBaseUnit(), id.getDescription(), id.getType());
            }
        };
    }

    /**
     * Rename a tag key for every metric beginning with a given prefix.
     *
     * @param meterNamePrefix Apply filter to metrics that begin with this name.
     * @param fromTagKey      Rename tags matching this key.
     * @param toTagKey        Rename to this key.
     * @return A tag-renaming filter.
     */
    static MeterFilter renameTag(String meterNamePrefix, String fromTagKey, String toTagKey) {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                if (!id.getName().startsWith(meterNamePrefix))
                    return id;

                List<Tag> tags = new ArrayList<>();
                for (Tag tag : id.getTags()) {
                    if (tag.getKey().equals(fromTagKey))
                        tags.add(Tag.of(toTagKey, tag.getValue()));
                    else tags.add(tag);
                }

                return new Meter.Id(id.getName(), tags, id.getBaseUnit(), id.getDescription(), id.getType());
            }
        };
    }

    /**
     * Suppress tags with a given tag key.
     *
     * @param tagKeys Keys of tags that should be suppressed.
     * @return A tag-suppressing filter.
     */
    static MeterFilter ignoreTags(String... tagKeys) {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                List<Tag> tags = stream(id.getTags().spliterator(), false)
                        .filter(t -> {
                            for (String tagKey : tagKeys) {
                                if (t.getKey().equals(tagKey))
                                    return false;
                            }
                            return true;
                        }).collect(Collectors.toList());

                return new Meter.Id(id.getName(), tags, id.getBaseUnit(), id.getDescription(), id.getType());
            }
        };
    }

    /**
     * Replace tag values according to the provided mapping for all matching tag keys. This can be used
     * to reduce the total cardinality of a tag by mapping some portion of tag values to something else.
     *
     * @param tagKey      The tag key for which replacements should be made
     * @param replacement The value to replace with
     * @param exceptions  All a matching tag with this value to retain its original value
     * @return A filter that replaces tag values.
     * @author Clint Checketts
     */
    static MeterFilter replaceTagValues(String tagKey, Function<String, String> replacement, String... exceptions) {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                List<Tag> tags = stream(id.getTags().spliterator(), false)
                        .map(t -> {
                            if (!t.getKey().equals(tagKey))
                                return t;
                            for (String exception : exceptions) {
                                if (t.getValue().equals(exception))
                                    return t;
                            }
                            return Tag.of(tagKey, replacement.apply(t.getValue()));
                        })
                        .collect(Collectors.toList());

                return new Meter.Id(id.getName(), tags, id.getBaseUnit(), id.getDescription(), id.getType());
            }
        };
    }

    /**
     * Can be used to build a whitelist of metrics matching certain criteria. Opposite of {@link #deny(Predicate)}.
     *
     * @param iff When a meter id matches, allow its inclusion, otherwise deny.
     * @return A meter filter that whitelists metrics matching a predicate.
     */
    static MeterFilter denyUnless(Predicate<Meter.Id> iff) {
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                return iff.test(id) ? MeterFilterReply.NEUTRAL : MeterFilterReply.DENY;
            }
        };
    }

    /**
     * When the given predicate is {@code true}, the meter should be present in published metrics.
     *
     * @param iff When a meter id matches, guarantee its inclusion in published metrics.
     * @return A filter that guarantees the inclusion of matching meters.
     */
    static MeterFilter accept(Predicate<Meter.Id> iff) {
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                return iff.test(id) ? MeterFilterReply.ACCEPT : MeterFilterReply.NEUTRAL;
            }
        };
    }

    /**
     * When the given predicate is {@code true}, the meter should NOT be present in published metrics.
     * Opposite of {@link #denyUnless(Predicate)}.
     *
     * @param iff When a meter id matches, guarantee its exclusion in published metrics.
     * @return A filter that guarantees the exclusion of matching meters.
     */
    static MeterFilter deny(Predicate<Meter.Id> iff) {
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                return iff.test(id) ? MeterFilterReply.DENY : MeterFilterReply.NEUTRAL;
            }
        };
    }

    /**
     * Include a meter in published metrics. Can be used as a subordinate action on another filter like
     * {@link #maximumAllowableTags}.
     *
     * @return A filter that guarantees the inclusion of all meters.
     */
    static MeterFilter accept() {
        return MeterFilter.accept(id -> true);
    }

    /**
     * Reject a meter in published metrics. Can be used as a subordinate action on another filter like
     * {@link #maximumAllowableTags}.
     *
     * @return A filter that guarantees the exclusion of all meters.
     */
    static MeterFilter deny() {
        return MeterFilter.deny(id -> true);
    }

    /**
     * Useful for cost-control in monitoring systems which charge directly or indirectly by the
     * total number of time series you generate.
     * <p>
     * While this filter doesn't discriminate between your most critical and less useful metrics in
     * deciding what to drop (all the metrics you intend to use should fit below this threshold),
     * it can effectively cap your risk of an accidentally high-cardiality metric costing too much.
     *
     * @param maximumTimeSeries The total number of unique name/tag permutations allowed before filtering kicks in.
     * @return A filter that globally limits the number of unique name and tag combinations.
     */
    static MeterFilter maximumAllowableMetrics(int maximumTimeSeries) {
        return new MeterFilter() {
            private final Set<Meter.Id> ids = ConcurrentHashMap.newKeySet();

            @Override
            public MeterFilterReply accept(Meter.Id id) {
                if (ids.size() > maximumTimeSeries)
                    return MeterFilterReply.DENY;

                ids.add(id);
                return ids.size() > maximumTimeSeries ? MeterFilterReply.DENY : MeterFilterReply.NEUTRAL;
            }
        };
    }

    /**
     * Places an upper bound on the number of tags produced by matching metrics.
     *
     * @param meterNamePrefix  Apply filter to metrics that begin with this name.
     * @param tagKey           The tag to place an upper bound on.
     * @param maximumTagValues The total number of tag values that are allowable.
     * @param onMaxReached     After the maximum number of tag values have been seen, apply this filter.
     * @return A meter filter that limits the number of tags produced by matching metrics.
     */
    static MeterFilter maximumAllowableTags(String meterNamePrefix, String tagKey, int maximumTagValues,
                                            MeterFilter onMaxReached) {
        return new MeterFilter() {
            private final Set<String> observedTagValues = new ConcurrentSkipListSet<>();

            @Override
            public MeterFilterReply accept(Meter.Id id) {
                if (id.getName().equals(meterNamePrefix)) {
                    String value = id.getTag(tagKey);
                    if (value != null) {
                        observedTagValues.add(value);
                        if (observedTagValues.size() > maximumTagValues) {
                            return onMaxReached.accept(id);
                        }
                    }
                }

                return MeterFilterReply.NEUTRAL;
            }

            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (observedTagValues.size() > maximumTagValues) {
                    return onMaxReached.configure(id, config);
                }
                return config;
            }
        };
    }

    /**
     * Meters that start with the provided name should NOT be present in published metrics.
     *
     * @param prefix When a meter name starts with the prefix, guarantee its exclusion in published metrics.
     * @return A filter that guarantees the exclusion of matching meters.
     */
    static MeterFilter denyNameStartsWith(String prefix) {
        return deny(id -> id.getName().startsWith(prefix));
    }

    /**
     * Set a maximum expected value on any {@link Timer} whose name begins with the given prefix.
     *
     * @param prefix Apply the maximum only to timers whose name begins with this prefix.
     * @param max    The maximum expected value of the timer.
     * @return A filter that applies a maximum expected value to a timer.
     */
    static MeterFilter maxExpected(String prefix, Duration max) {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (Meter.Type.TIMER.equals(id.getType()) && id.getName().startsWith(prefix)) {
                    return DistributionStatisticConfig.builder()
                            .maximumExpectedValue(max.toNanos())
                            .build()
                            .merge(config);
                }
                return config;
            }
        };
    }

    /**
     * Set a maximum expected value on any {@link DistributionSummary} whose name begins with the given prefix.
     *
     * @param prefix Apply the maximum only to distribution summaries whose name begins with this prefix.
     * @param max    The maximum expected value of the distribution summmary.
     * @return A filter that applies a maximum expected value to a distribution summary.
     */
    static MeterFilter maxExpected(String prefix, long max) {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (Meter.Type.DISTRIBUTION_SUMMARY.equals(id.getType()) && id.getName().startsWith(prefix)) {
                    return DistributionStatisticConfig.builder()
                            .maximumExpectedValue(max)
                            .build()
                            .merge(config);
                }
                return config;
            }
        };
    }

    /**
     * Set a minimum expected value on any {@link Timer} whose name begins with the given prefix.
     *
     * @param prefix Apply the minimum only to timers whose name begins with this prefix.
     * @param min    The minimum expected value of the timer.
     * @return A filter that applies a minimum expected value to a timer.
     */
    static MeterFilter minExpected(String prefix, Duration min) {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (Meter.Type.TIMER.equals(id.getType()) && id.getName().startsWith(prefix)) {
                    return DistributionStatisticConfig.builder()
                            .minimumExpectedValue(min.toNanos())
                            .build()
                            .merge(config);
                }
                return config;
            }
        };
    }

    /**
     * Set a minimum expected value on any {@link DistributionSummary} whose name begins with the given prefix.
     *
     * @param prefix Apply the minimum only to distribution summaries whose name begins with this prefix.
     * @param min    The minimum expected value of the distribution summmary.
     * @return A filter that applies a minimum expected value to a distribution summary.
     */
    static MeterFilter minExpected(String prefix, long min) {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (Meter.Type.DISTRIBUTION_SUMMARY.equals(id.getType()) && id.getName().startsWith(prefix)) {
                    return DistributionStatisticConfig.builder()
                            .minimumExpectedValue(min)
                            .build()
                            .merge(config);
                }
                return config;
            }
        };
    }

    /**
     * @param id Id with {@link MeterFilter#map} transformations applied.
     * @return After all transformations, should a real meter be registered for this id, or should it be no-op'd.
     */
    default MeterFilterReply accept(Meter.Id id) {
        return MeterFilterReply.NEUTRAL;
    }

    /**
     * @param id Id to transform.
     * @return Transformations to any part of the id.
     */
    default Meter.Id map(Meter.Id id) {
        return id;
    }

    /**
     * This is only called when filtering new timers and distribution summaries (i.e. those meter types
     * that use {@link DistributionStatisticConfig}).
     *
     * @param id     Id with {@link MeterFilter#map} transformations applied.
     * @param config A histogram configuration guaranteed to be non-null.
     * @return Overrides to any part of the histogram config, when applicable.
     */
    @Nullable
    default DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
        return config;
    }
}
