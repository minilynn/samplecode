/**
 * 
 */
package com.gul.experience.samplecode.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NIO样例代码客户端
 * 
 * @author Lynn
 */
public class Client implements Runnable {
	private final static Logger log = LoggerFactory.getLogger(Client.class);
	// 空闲计数器,如果空闲超过10次,将检测server是否中断连接.
	private static int idleCounter = 0;
	private Selector selector;
	private SocketChannel socketChannel;
	private ByteBuffer buff = ByteBuffer.allocate(1024);

	public static void main(String[] args) throws IOException {
		Client client = new Client();
		new Thread(client).start();
	}

	public Client() throws IOException {
		// 同样的,注册闹钟.
		this.selector = Selector.open();

		// 连接远程server
		socketChannel = SocketChannel.open();
		// 如果快速的建立了连接,返回true.如果没有建立,则返回false,并在连接后出发Connect事件.
		Boolean isConnected = socketChannel.connect(new InetSocketAddress("localhost", 3562));
		socketChannel.configureBlocking(false);
		SelectionKey key = socketChannel.register(selector, SelectionKey.OP_READ);

		if (isConnected) {
			this.sendFirstMsg();
		} else {
			// 如果连接还在尝试中,则注册connect事件的监听. connect成功以后会出发connect事件.
			key.interestOps(SelectionKey.OP_CONNECT);
		}
	}

	@Override
	public void run() {
		while (true) {
			try {
				// 阻塞,等待事件发生,或者1秒超时. num为发生事件的数量.
				int num = this.selector.select(1000);
				if (num == 0) {
					idleCounter++;
					if (idleCounter > 10) {
						// 如果server断开了连接,发送消息将失败.
						try {
							this.sendFirstMsg();
						} catch (ClosedChannelException e) {
							log.error("发送消息失败", e);
							this.socketChannel.close();
							return;
						}
					}
					continue;
				} else {
					idleCounter = 0;
				}

				Set<SelectionKey> keys = this.selector.selectedKeys();
				Iterator<SelectionKey> it = keys.iterator();
				while (it.hasNext()) {
					SelectionKey key = it.next();
					it.remove();
					if (key.isConnectable()) {
						// socket connected
						SocketChannel sc = (SocketChannel) key.channel();
						if (sc.isConnectionPending()) {
							sc.finishConnect();
						}
						// send first message;
						this.sendFirstMsg();
					}
					if (key.isReadable()) {
						// msg received.
						SocketChannel sc = (SocketChannel) key.channel();
						this.buff = ByteBuffer.allocate(1024);
						int count = sc.read(buff);
						if (count < 0) {
							sc.close();
							Thread.sleep(1000);
							continue;
						}
						// 切换buffer到读状态,内部指针归位.
						buff.flip();
						String msg = Charset.forName("UTF-8").decode(buff).toString();
						if (log.isDebugEnabled()) {
							log.debug("接收到消息：" + msg + "，消息来源：" + sc.getRemoteAddress());
						}

						Thread.sleep(1000);
						sc.write(ByteBuffer.wrap(msg.getBytes(Charset.forName("UTF-8"))));
						// 清空buffer
						buff.clear();
					}
				}
			} catch (Exception e) {
				log.error("与服务端通信异常", e);
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e1) {
					log.error("暂停当前线程执行异常", e);
				}
			}
		}
	}

	public void sendFirstMsg() throws IOException {
		String msg = "Hello NIO.";
		socketChannel.write(ByteBuffer.wrap(msg.getBytes(Charset.forName("UTF-8"))));
	}
}
