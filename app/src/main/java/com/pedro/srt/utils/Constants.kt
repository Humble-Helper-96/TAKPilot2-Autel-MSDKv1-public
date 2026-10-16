/*
 * Copyright (C) 2024 pedroSG94.
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

package com.pedro.srt.utils

/**
 * Created by pedro on 22/8/23.
 */
object Constants {
  /**
   * TAKPILOT2 CHANGE: 1500 -> 1316. This is the SIZING INPUT for an outgoing SRT packet, not
   * the size of one. `SrtSender` takes `MTU - 16` and fits whole 188-byte MPEG-TS packets in
   * it, so what actually goes on the wire is:
   *
   * ```
   *   IP(20) + UDP(8) + SRT(16) + N x 188
   *   N = (MTU - 16) / 188
   *   1500 -> N=7 -> 1360 bytes        1316 -> N=6 -> 1172 bytes
   * ```
   *
   * ⚠ **THE LIBRARY DOES NOT SUBTRACT THE IP AND UDP HEADERS.** libsrt treats its MSS as the
   * whole datagram and takes 28 bytes off for them; this code does not, so 1500 here means
   * 1360 on the wire — larger than an ordinary 1500-byte path allows, before any tunnel.
   *
   * Measured from a Smart Controller to a production SRT server: the path MTU is
   * **1342 bytes** (1342 passes with DF set, 1343 fails). The 1360-byte packets were refused
   * by the local stack with EMSGSIZE, and because only a LARGE frame fills a datagram to
   * seven TS packets, the stream connected, ran, and then died on a keyframe.
   *
   * 1172 leaves 170 bytes of margin on that path and stays under the worst MTU a cellular or
   * VPN path realistically presents. The cost is one more packet per six: header overhead
   * goes from 3.2% to 3.8%.
   */
  const val MTU = 1316

  /**
   * TAKPILOT2 CHANGE: the RECEIVE buffer, which must not shrink with [MTU]. It sizes the
   * array an inbound datagram is read into, and a datagram longer than the array is
   * TRUNCATED SILENTLY. What arrives here is the handshake and the control packets, and they
   * are sized by the server, not by us.
   */
  const val READ_BUFFER = 1500
  const val SYSTEM_CLOCK_FREQ = 27000000
}