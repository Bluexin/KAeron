/*
 * Copyright (C) 2019-2020 Arnaud 'Bluexin' Solé
 *
 * This file is part of KAeron.
 *
 * KAeron is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * KAeron is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with KAeron.  If not, see <https://www.gnu.org/licenses/>.
 */

package be.bluexin.kaeron

import com.github.javafaker.Faker
import io.aeron.Aeron
import io.aeron.driver.MediaDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import mu.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Running the tests does **NOT** require this !
 */
object StartAeron {
    @JvmStatic
    fun main(args: Array<String>) {
        MediaDriver.launch()
    }
}

internal class AeronWrappersTest {

    private val aeronDriver = MediaDriver.launchEmbedded()
    private val logger = KotlinLogging.logger { }

    @AfterAll
    fun tearDown() {
        this.aeronDriver.close()
    }

    @Test
    fun `producing and consuming through channels on the same stream yield identical contents`() {
        runBlocking {
            val aeron = AeronConfig(
                client = Aeron.connect(
                    Aeron.Context()
                        .aeronDirectoryName(aeronDriver.aeronDirectoryName())
                        .errorHandler { logger.warn(it) { "Aeron error" } }
                        .availableImageHandler { logger.info { "Aeron is available" } }
                        .unavailableImageHandler { logger.info { "Aeron went down" } }
                        .availableCounterHandler { _, registrationId, counterId -> logger.info { "Aeron conductor available: $registrationId $counterId" } }
                        .unavailableCounterHandler { _, registrationId, counterId -> logger.info { "Aeron conductor unavailable: $registrationId $counterId" } }
                ),
                url = "aeron:udp?endpoint=localhost:40123", // TODO: config
                stream = 10
            )

            val writing = with(Faker()) { Array(150) { beer().name().toByteArray() } }

            val (reader, read) = readAsync(aeron)
            val writer = write(aeron, writing)

            logger.info { "Done setting up" }
            reader.start()
            writer.start()
            logger.info { "finished starting" }
            writer.join()
            logger.info { "finished writing" }
            reader.cancelAndJoin()
            logger.info { "finished reading" }

            Assertions.assertArrayEquals(writing, read.toTypedArray())
        }
    }

    @UseExperimental(ExperimentalCoroutinesApi::class)
    private fun CoroutineScope.write(config: AeronConfig, elements: Array<ByteArray>): Job {
        val channel = Channel<ByteArray>(Channel.UNLIMITED)

        return launch(start = CoroutineStart.LAZY) {
            aeronProducer(config, channel)
            for (w in elements) {
                channel.offer(w)
                delay(10)
            }
            channel.close()
        }
    }

    @UseExperimental(ExperimentalCoroutinesApi::class)
    private fun CoroutineScope.readAsync(config: AeronConfig): Pair<Job, Queue<ByteArray>> {
        val read: Queue<ByteArray> = ConcurrentLinkedQueue()
        return launch(start = CoroutineStart.LAZY) { for (update in aeronConsumer(config)) read += update } to read
    }
}