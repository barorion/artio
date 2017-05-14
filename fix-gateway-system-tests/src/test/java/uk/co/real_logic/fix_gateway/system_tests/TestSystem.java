/*
 * Copyright 2015-2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.system_tests;

import uk.co.real_logic.fix_gateway.Reply;
import uk.co.real_logic.fix_gateway.engine.LockStepFramerEngineScheduler;
import uk.co.real_logic.fix_gateway.library.FixLibrary;
import uk.co.real_logic.fix_gateway.library.LibraryConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertThat;
import static uk.co.real_logic.fix_gateway.FixMatchers.isConnected;
import static uk.co.real_logic.fix_gateway.Timing.DEFAULT_TIMEOUT_IN_MS;
import static uk.co.real_logic.fix_gateway.Timing.assertEventuallyTrue;
import static uk.co.real_logic.fix_gateway.system_tests.SystemTestUtil.LIBRARY_LIMIT;

public class TestSystem
{
    private final List<FixLibrary> libraries;
    private final LockStepFramerEngineScheduler scheduler;

    public TestSystem(final LockStepFramerEngineScheduler scheduler, final FixLibrary... libraries)
    {
        this.scheduler = scheduler;
        this.libraries = new ArrayList<>();
        Collections.addAll(this.libraries, libraries);
    }

    public TestSystem(final FixLibrary... libraries)
    {
        this(null, libraries);
    }

    public void poll()
    {
        if (scheduler != null)
        {
            scheduler.doFramerWork();
            scheduler.doFramerWork();
        }
        libraries.forEach((library) -> library.poll(LIBRARY_LIMIT));
    }

    public void assertConnected()
    {
        libraries.forEach((library) -> assertThat(library, isConnected()));
    }

    public void close(final FixLibrary library)
    {
        library.close();
        libraries.remove(library);
    }

    public FixLibrary add(final FixLibrary library)
    {
        libraries.add(library);
        return library;
    }

    public FixLibrary connect(final LibraryConfiguration configuration)
    {
        final FixLibrary library = FixLibrary.connect(configuration);
        add(library);
        assertEventuallyTrue(
            () -> "Unable to connect to engine",
            () ->
            {
                poll();

                return library.isConnected();
            },
            DEFAULT_TIMEOUT_IN_MS,
            () -> close(library));

        return library;
    }

    public void awaitReply(final Reply<?> reply)
    {
        assertEventuallyTrue(
            "No reply from: " + reply,
            () ->
            {
                poll();

                return !reply.isExecuting();
            });
    }
}
