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

import io.aeron.Aeron
import io.aeron.FragmentAssembler
import io.aeron.Publication
import io.aeron.logbuffer.FragmentHandler
import io.aeron.status.ChannelEndpointStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import mu.KotlinLogging.logger
import org.agrona.BufferUtil
import org.agrona.concurrent.BackoffIdleStrategy
import org.agrona.concurrent.IdleStrategy
import org.agrona.concurrent.UnsafeBuffer
import java.util.concurrent.TimeUnit

data class AeronConfig(
    val client: Aeron,
    val url: String,
    val stream: Int,
    val bufferSize: Int = 1024,
    val idleStrategy: IdleStrategy = BackoffIdleStrategy(
        100,
        10,
        TimeUnit.MICROSECONDS.toNanos(1),
        TimeUnit.MICROSECONDS.toNanos(100)
    )
)

/**
 * Creates an Aeron producer, relaying everything from [input] to the Aeron bus.
 */
@ExperimentalCoroutinesApi
fun CoroutineScope.aeronProducer(config: AeronConfig, input: ReceiveChannel<ByteArray>): Job =
    launch(Dispatchers.IO + CoroutineName("Aeron Producer")) {
        val logger = logger("Aeron producer")
        logger.info { "Booting up the Aeron producer" }

        config.client.addExclusivePublication(config.url, config.stream).use { pub ->
            val buff = UnsafeBuffer(BufferUtil.allocateDirectAligned(config.bufferSize, 64))
            val idleStrategy = config.idleStrategy

            logger.info { "Waiting for active connection to Aeron" }
            while (isActive && !pub.isConnected) {
                when (pub.channelStatus()) {
                    ChannelEndpointStatus.CLOSING, ChannelEndpointStatus.ERRORED -> return@use
                    else -> idleStrategy.idle()
                }
            }
            logger.info { "Starting to send to Aeron" }

            outer@ for (i in input) {
                logger.debug { "Sending ${String(i)}" }
                buff.putBytes(0, i)
                var res = pub.offer(buff, 0, i.size)
                while (isActive && res <= 0) {
                    when (res) {
                        Publication.CLOSED -> {
                            input.cancel()
                            break@outer
                        }
                        else -> {
                            idleStrategy.idle(res.toInt())
                            res = pub.offer(buff, 0, i.size)
                        }
                    }
                }
                logger.debug { "Sent ${String(i)}" }
                if (!isActive) break
                idleStrategy.reset()
            }
        }

    logger.info { "Stopping to send to Aeron (canceled: ${!isActive}, input closed: ${input.isClosedForReceive})" }
}

/**
 * Creates an Aeron consumer, relaying everything from the Aeron bus to the returned Channel.
 */
@ExperimentalCoroutinesApi
fun CoroutineScope.aeronConsumer(config: AeronConfig): ReceiveChannel<ByteArray> =
    produce(Dispatchers.IO + CoroutineName("Aeron Consumer"), capacity = Channel.UNLIMITED) {
        val logger = logger("Aeron consumer")
        logger.info { "Booting up the Aeron consumer" }

        val idleStrategy = config.idleStrategy
        val fragmentHandler = FragmentAssembler(FragmentHandler { buffer, offset, length, _ ->
            val data = ByteArray(length)
            buffer.getBytes(offset, data)
            logger.debug { "Received ${String(data)}" }
            offer(data)
        })

        config.client.addSubscription(
            config.url,
            config.stream,
            { logger.info { "Aeron is available" }  },
            { logger.info { "Aeron went down" }  }
        ).use { sub ->
            while (isActive && !sub.isConnected) {
                when (sub.channelStatus()) {
                    ChannelEndpointStatus.CLOSING, ChannelEndpointStatus.ERRORED -> return@use
                    else -> idleStrategy.idle()
                }
            }
            idleStrategy.reset()

            logger.info { "Starting to consume from Aeron" }

            while (isActive) {
                val fragmentsRead = sub.poll(fragmentHandler, 10)
                if (isActive) idleStrategy.idle(fragmentsRead)
            }
        }

        logger.info { "Stopping to consume from Aeron (canceled: ${!isActive}, output closed: ${channel.isClosedForSend})" }
    }
