/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.coyote.http11;

import static org.jboss.web.CoyoteMessages.MESSAGES;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.apache.coyote.InputBuffer;
import org.apache.coyote.Request;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.NioEndpoint;
import org.apache.tomcat.util.net.SocketStatus;
import org.jboss.web.CoyoteLogger;

/**
 * {@code InternalNioInputBuffer}
 * <p>
 * Implementation of InputBuffer which provides HTTP request header parsing as
 * well as transfer decoding.
 * </p>
 * 
 * Created on Dec 14, 2011 at 9:06:18 AM
 * 
 * @author <a href="mailto:nbenothm@redhat.com">Nabil Benothman</a>
 */
public class InternalNioInputBuffer extends AbstractInternalInputBuffer {

	/**
	 * Underlying channel.
	 */
	protected NioChannel channel;

	/**
	 * Non blocking mode.
	 */
	protected boolean nonBlocking = false;

	/**
	 * Non blocking mode.
	 */
	protected boolean available = false;

	/**
	 * NIO end point.
	 */
	protected NioEndpoint endpoint = null;

    /**
     * NIO processor.
     */
    protected Http11NioProcessor processor;

	/**
	 * The completion handler used for asynchronous read operations
	 */
	private CompletionHandler<Integer, NioChannel> completionHandler;

    /**
     * Semaphore used for waiting for completion handler.
     */
    private Semaphore semaphore = new Semaphore(1);
    
    /**
	 * Create a new instance of {@code InternalNioInputBuffer}
	 * 
	 * @param request
	 * @param headerBufferSize
	 * @param endpoint
	 */
	public InternalNioInputBuffer(Http11NioProcessor processor, Request request, int headerBufferSize, NioEndpoint endpoint) {
		super(request, headerBufferSize);
		this.endpoint = endpoint;
        this.processor = processor;
		this.init();
	}

	/**
	 * 
	 */
	protected void init() {
		this.inputBuffer = new InputBufferImpl();
		this.readTimeout = (endpoint.getSoTimeout() > 0 ? endpoint.getSoTimeout()
				: Integer.MAX_VALUE);

		// Initialize the completion handler
		this.completionHandler = new CompletionHandler<Integer, NioChannel>() {

			@Override
			public synchronized void completed(Integer nBytes, NioChannel attachment) {
			    if (nBytes < 0) {
			        failed(new ClosedChannelException(), attachment);
			        return;
			    }

			    if (nBytes > 0) {
			        bbuf.flip();
			        bbuf.get(buf, pos, nBytes);
			        lastValid = pos + nBytes;
			        semaphore.release();
			        if (!processor.isProcessing() && processor.getReadNotifications()) {
			            if (!endpoint.processChannel(attachment, SocketStatus.OPEN_READ)) {
			                endpoint.closeChannel(attachment);
			            }
			        }
			    }
			}

			@Override
			public void failed(Throwable exc, NioChannel attachment) {
			    processor.getResponse().setErrorException(exc);
			    endpoint.removeEventChannel(attachment);
                semaphore.release();
			    if (!endpoint.processChannel(attachment, SocketStatus.ERROR)) {
			        endpoint.closeChannel(attachment);
			    }
			}
		};
	}

	/**
	 * Set the underlying channel.
	 * 
	 * @param channel
	 */
	public void setChannel(NioChannel channel) {
		this.channel = channel;
	}

	/**
	 * Get the underlying socket input stream.
	 * 
	 * @return the channel
	 */
	public NioChannel getChannel() {
		return channel;
	}

	/**
	 * Set the non blocking flag.
	 * 
	 * @param nonBlocking
	 */
	public void setNonBlocking(boolean nonBlocking) {
		this.nonBlocking = nonBlocking;
	}

	/**
	 * Get the non blocking flag value.
	 * 
	 * @return true if the buffer is non-blocking else false
	 */
	public boolean getNonBlocking() {
		return nonBlocking;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.coyote.http11.AbstractInternalInputBuffer#recycle()
	 */
	public void recycle() {
		super.recycle();
		bbuf.clear();
		channel = null;
		available = false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.coyote.http11.AbstractInternalInputBuffer#nextRequest()
	 */
	public boolean nextRequest() {
		boolean result = super.nextRequest();
		available = false;
        if (nonBlocking) {
            semaphore.release();
        }
        nonBlocking = false;

		return result;
	}

	/**
	 * Read the request line. This function is meant to be used during the HTTP
	 * request header parsing. Do NOT attempt to read the request body using it.
	 * 
	 * @param useAvailableData
	 * 
	 * @throws IOException
	 *             If an exception occurs during the underlying socket read
	 *             operations, or if the given buffer is not big enough to
	 *             accommodate the whole line.
	 * @return true if data is properly fed; false if no data is available
	 *         immediately and thread should be freed
	 */
	public boolean parseRequestLine(boolean useAvailableData) throws IOException {

		int start = 0;
		// Skipping blank lines

		byte chr = 0;
		do {
			// Read new bytes if needed
			if (pos >= lastValid) {
				if (useAvailableData) {
					return false;
				}
				if (!fill()) {
					throw new EOFException(MESSAGES.eofError());
				}
			}

			chr = buf[pos++];
		} while ((chr == Constants.CR) || (chr == Constants.LF));

		pos--;

		// Mark the current buffer position
		start = pos;

		if (pos >= lastValid) {
			if (useAvailableData) {
				return false;
			}
			if (!fill()) {
				throw new EOFException(MESSAGES.eofError());
			}
		}

		// Reading the method name
		// Method name is always US-ASCII

		boolean space = false;

		while (!space) {

			// Read new bytes if needed
			if (pos >= lastValid) {
				if (!fill()) {
					throw new EOFException(MESSAGES.eofError());
				}
			}

			// Spec says single SP but it also says be tolerant of HT
			if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
				space = true;
				request.method().setBytes(buf, start, pos - start);
			}

			pos++;
		}

		// Spec says single SP but also says be tolerant of multiple and/or HT
		while (space) {
			// Read new bytes if needed
			if (pos >= lastValid) {
				if (!fill()) {
					throw new EOFException(MESSAGES.eofError());
				}
			}
			if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
				pos++;
			} else {
				space = false;
			}
		}

		// Mark the current buffer position
		start = pos;
		int end = 0;
		int questionPos = -1;

		// Reading the URI
		boolean eol = false;

		while (!space) {
			// Read new bytes if needed
			if (pos >= lastValid) {
				if (!fill())
					throw new EOFException(MESSAGES.eofError());
			}

			// Spec says single SP but it also says be tolerant of HT
			if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
				space = true;
				end = pos;
			} else if ((buf[pos] == Constants.CR) || (buf[pos] == Constants.LF)) {
				// HTTP/0.9 style request
				eol = true;
				space = true;
				end = pos;
			} else if ((buf[pos] == Constants.QUESTION) && (questionPos == -1)) {
				questionPos = pos;
			}

			pos++;
		}

		request.unparsedURI().setBytes(buf, start, end - start);
		if (questionPos >= 0) {
			request.queryString().setBytes(buf, questionPos + 1, end - questionPos - 1);
			request.requestURI().setBytes(buf, start, questionPos - start);
		} else {
			request.requestURI().setBytes(buf, start, end - start);
		}

		// Spec says single SP but also says be tolerant of multiple and/or HT
		while (space) {
			// Read new bytes if needed
			if (pos >= lastValid) {
				if (!fill())
					throw new EOFException(MESSAGES.eofError());
			}
			if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
				pos++;
			} else {
				space = false;
			}
		}

		// Mark the current buffer position
		start = pos;
		end = 0;

		//
		// Reading the protocol
		// Protocol is always US-ASCII
		//
		while (!eol) {
			// Read new bytes if needed
			if (pos >= lastValid) {
				if (!fill()) {
					throw new EOFException(MESSAGES.eofError());
				}
			}

			if (buf[pos] == Constants.CR) {
				end = pos;
			} else if (buf[pos] == Constants.LF) {
				if (end == 0)
					end = pos;
				eol = true;
			}

			pos++;
		}

		if ((end - start) > 0) {
			request.protocol().setBytes(buf, start, end - start);
		} else {
			request.protocol().setString("");
		}

		return true;
	}

	/**
	 * Available bytes (note that due to encoding, this may not correspond )
	 */
	public void useAvailable() {
		available = true;
	}

	/**
	 * Available bytes in the buffer ? (these may not translate to application
	 * readable data)
	 * 
	 * @return the number of available bytes in the buffer
	 */
	public boolean available() {
		return (lastValid - pos > 0);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.apache.coyote.InputBuffer#doRead(org.apache.tomcat.util.buf.ByteChunk
	 * , org.apache.coyote.Request)
	 */
	public int doRead(ByteChunk chunk, Request req) throws IOException {
		return (lastActiveFilter == -1) ? inputBuffer.doRead(chunk, req)
				: activeFilters[lastActiveFilter].doRead(chunk, req);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.coyote.http11.AbstractInternalInputBuffer#fill()
	 */
	protected boolean fill() throws IOException {
		return (fill0() >= 0);
	}

    private int fill0() throws IOException {
        int nRead = 0;
        // Reading from client
        if (nonBlocking) {
            if (semaphore.tryAcquire()) {
                // Prepare the internal input buffer for reading
                prepare();
                try {
                    channel.read(bbuf, readTimeout, TimeUnit.MILLISECONDS, channel, this.completionHandler);
                } catch (Exception e) {
                    processor.getResponse().setErrorException(e);
                    if (CoyoteLogger.HTTP_LOGGER.isDebugEnabled()) {
                        CoyoteLogger.HTTP_LOGGER.errorWithNonBlockingRead(e);
                    }
                }
                nRead = lastValid - pos;
            } else if (nRead == 0 && !available) {
                // If there's nothing and flow control is not used, autoblock 
                try {
                    if (semaphore.tryAcquire(readTimeout, unit))
                        semaphore.release();
                } catch (InterruptedException e) {
                    // Ignore
                }
                nRead = lastValid - pos;
            }
        } else {
            // Prepare the internal input buffer for reading
            prepare();
            nRead = blockingRead(readTimeout, unit);
            if (nRead > 0) {
                bbuf.flip();
                if (nRead > (buf.length - end)) {
                    // An alternative is to bbuf.setLimit(buf.length - end) before the read,
                    // which may be less efficient
                    buf = new byte[buf.length];
                    end = 0;
                    pos = end;
                    lastValid = pos;
                }
                bbuf.get(buf, pos, nRead);
                lastValid = pos + nRead;
            } else if (nRead == NioChannel.OP_STATUS_CLOSED) {
                throw new EOFException(MESSAGES.failedRead());
            } else if (nRead == NioChannel.OP_STATUS_READ_TIMEOUT) {
                throw new SocketTimeoutException(MESSAGES.failedRead());
            }
        }
        return nRead;
    }

    /**
	 * Prepare the input buffer for reading
	 */
	private void prepare() {
		bbuf.clear();
		if (parsingHeader) {
			if (lastValid == buf.length) {
				throw MESSAGES.requestHeaderTooLarge();
			}
		} else {
			pos = end;
			lastValid = pos;
		}
	}

	/**
	 * Close the channel
	 */
	private void close(NioChannel channel) {
		endpoint.closeChannel(channel);
	}

	/**
	 * Read a sequence of bytes in blocking mode from he current channel
	 * 
	 * @param bb
	 * @return the number of bytes read or -1 if the end of the stream was
	 *         reached
	 */
	private int blockingRead(long timeout, TimeUnit unit) {
		int nr = 0;
		try {
			long readTimeout = timeout > 0 ? timeout : Integer.MAX_VALUE;
			nr = this.channel.readBytes(bbuf, readTimeout, unit);
			if (nr < 0) {
				close(channel);
			}
		} catch (Exception e) {
			if (CoyoteLogger.HTTP_LOGGER.isDebugEnabled()) {
                CoyoteLogger.HTTP_LOGGER.errorWithBlockingRead(e);
			}
		}
		return nr;
	}

	/**
	 * This class is an input buffer which will read its data from an input
	 * stream.
	 */
	protected class InputBufferImpl implements InputBuffer {

		/**
		 * Read bytes into the specified chunk.
		 */
		public int doRead(ByteChunk chunk, Request req) throws IOException {

            if (pos >= lastValid) {
                int nRead = fill0();
                if (nRead < 0) {
                    return -1;
                } else if (nRead == 0) {
                    return 0;
                }
            }

            if (nonBlocking) {
                synchronized (completionHandler) {
                    int length = lastValid - pos;
                    chunk.setBytes(buf, pos, length);
                    pos = lastValid;
                    return (length);
                }
		    } else {
		        int length = lastValid - pos;
		        chunk.setBytes(buf, pos, length);
		        pos = lastValid;
		        return (length);
		    }
		}
	}
}
