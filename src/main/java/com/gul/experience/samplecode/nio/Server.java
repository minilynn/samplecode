/**
 * 
 */
package com.gul.experience.samplecode.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NIO样例代码服务端
 * 
 * @author Lynn
 */
public class Server {
	private final static Logger log = LoggerFactory.getLogger(Server.class);
	// 待处理的连接请求
	private ConcurrentLinkedQueue<SelectionKey> connQueue = new ConcurrentLinkedQueue<SelectionKey>();
	// 待处理的连接请求
	private ConcurrentLinkedQueue<SelectionKey> reqQueue = new ConcurrentLinkedQueue<SelectionKey>();
	// 接收连接请求的选择器
	private Selector connSelector;
	// 接收服务请求的选择器集合
	private List<Selector> reqSelectors = new ArrayList<Selector>();
	// 接收连接请求的服务端通道
	private ServerSocketChannel channel;
	// 服务器处理器内核数
	private final int processorNum = 3;
	// 服务请求处理线程池大小
	private final int workerNum = 3;
	// 服务器端口
	private final int port = 3562;

	/**
	 * 1. 打开服务器的连接端口；<br />
	 * 2. 然后启动线程ConnectionHander，使用select方法阻塞等待收集所有的连接请求，<br />
	 * 在收到连接请求后，获取对应的SelectionKey对象放到数组connQueue中（并未直接建立<br />
	 * 连接），并同时唤醒reqSelectors中一个对应的Selector对象；<br />
	 * 3. 根据配置的CPU数量，创建对应的Selector对象，存储到m_reqSelector中，并分别<br />
	 * 与一个RequestHandler对象关联，启动对应的线程，使用select方法阻塞等待处理读请求；<br />
	 * 4. 在第二步中被唤醒的RequestHandler线程从connQueue中获取SelectionKey，获取对应<br />
	 * 的Channel对象，并建立连接，然后注册感兴趣的事件为Read事件。然后再获取该Channel<br />
	 * 中所有的Read事件的SelectionKey列表，并添加到reqQueue队列中（不直接读数据）。然后<br />
	 * 该线程进入下一个循环，在select方法处阻塞等待下一次事件到来；<br />
	 * 5. 创建ProcessManager线程，在该线程中创建一个线程池，并依次读取reqQueue中的待处理<br />
	 * 的Read事件的SelectionKey，然后放到线程池处理队列中执行。
	 * 
	 * @throws IOException
	 */
	public void listen() throws IOException {
		// 第一步
		connSelector = Selector.open();
		channel = ServerSocketChannel.open();
		channel.configureBlocking(false);
		channel.socket().bind(new InetSocketAddress(port));
		channel.register(connSelector, SelectionKey.OP_ACCEPT);

		// 第二步
		new Thread(new ConnectionHander()).start();
		// 第三、四步
		creatRequestHanders();
		// 第五步
		new Thread(new ProcessManager()).start();
	}

	/**
	 * 步骤二<br />
	 * 负责接收请求的单一线程（只有一个线程实例）
	 *
	 * @author Lynn
	 */
	class ConnectionHander implements Runnable {
		int idx = 0;

		@Override
		public void run() {
			log.debug("准备接收连接请求");
			int keys = 0;
			while (true) {
				try {
					// 等待客户端连接
					keys = connSelector.select();
					if (keys > 0) {
						log.debug("Selected Keys Count: " + keys);
						Set<SelectionKey> selectKeys = connSelector.selectedKeys();
						Iterator<SelectionKey> it = selectKeys.iterator();

						while (it.hasNext()) {
							SelectionKey key = it.next();
							if (key.isValid() && key.isAcceptable()) {
								connQueue.add(key);
								int num = reqSelectors.size();
								// 防止监听request的进程都在堵塞中
								reqSelectors.get(idx).wakeup();
								idx = (idx + 1) % num;
							}
						}
					}
				} catch (IOException e) {
					log.error("接收连接请求异常", e);
				}
			}
		}
	}

	/**
	 * 步骤三<br />
	 * 创建监听服务请求的选择器列表，选择器个数为内核数
	 */
	private void creatRequestHanders() {
		try {
			for (int i = 0; i < processorNum; ++i) {
				// 创建选择器
				Selector slt = Selector.open();
				reqSelectors.add(slt);
				// 创建服务请求处理器，并注入选择器对象
				RequestHander req = new RequestHander();
				req.setSelector(slt);
				// 启动新线程，接收服务请求
				new Thread(req).start();
			}
		} catch (IOException e) {
			log.error("创建监听服务请求的选择器异常", e);
		}
	}

	/**
	 * 步骤四<br />
	 * 连接请求处理与服务请求接收线程
	 *
	 * @author Lynn
	 */
	class RequestHander implements Runnable {
		// 该线程注入的选择器对象
		private Selector selector;

		public void setSelector(Selector slt) {
			selector = slt;
		}

		public void run() {
			try {
				SelectionKey key;
				log.debug("运行连接请求处理与服务请求接收线程，线程ID：" + Thread.currentThread().getId());
				while (true) {
					selector.select();
					// 首先处理连接请求，并注册连接后的Channel注册到选择器
					// 这种处理连接的方式，可能造成某一个选择器注册的Channel或选择键比其他线程多，导致线程内的繁忙程度不一致
					while ((key = connQueue.poll()) != null) {
						ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
						// 接受一个连接
						SocketChannel sc = ssc.accept();
						sc.configureBlocking(false);
						sc.register(selector, SelectionKey.OP_READ);
						if (log.isDebugEnabled()) {
							log.debug("接受一个客户端连接，处理连接请求的线程ID：" + Thread.currentThread().getId());
						}
					}

					// 再处理服务请求的选择键，并将选择键放入待处理服务队列中
					Set<SelectionKey> keys = selector.selectedKeys();
					Iterator<SelectionKey> it = keys.iterator();
					while (it.hasNext()) {
						SelectionKey keytmp = it.next();
						it.remove();
						if (keytmp.isReadable()) {
							reqQueue.add(keytmp);
						}
					}
				}
			} catch (IOException e) {
				log.error("处理连接请求，并接收服务请求处理异常", e);
			}
		}
	}

	/**
	 * 步骤五<br />
	 * 创建处理服务请求的线程池，并从待处理服务队列中获取待处理选择键，执行服务。<br />
	 * 该线程为单实例，在该实例中创建了一个线程池，在线程池中处理真正的业务
	 *
	 * @author Lynn
	 *
	 */
	class ProcessManager implements Runnable {
		private ExecutorService m_workPool;

		public ProcessManager() {
			m_workPool = Executors.newFixedThreadPool(workerNum);
		}

		public void run() {
			SelectionKey key;
			// 启动之后，该循环一直在执行，消耗CPU
			while (true) {
				// 太消耗cpu//应该要加一个wait，但是这样就有锁了
				while ((key = reqQueue.poll()) != null) {
					ProcessRequest preq = new ProcessRequest();
					preq.setKey(key);
					m_workPool.execute(preq);
				}
			}
		}
	}

	/**
	 * 服务请求处理线程
	 *
	 * @author Lynn
	 */
	class ProcessRequest implements Runnable {
		SelectionKey key;

		public void setKey(SelectionKey key) {
			this.key = key;
		}

		public void run() {
			ByteBuffer buffer = ByteBuffer.allocate(1024);
			SocketChannel sc = (SocketChannel) key.channel();
			String msg = null;
			try {
				int readBytes = 0;
				int ret;
				try {
					while ((ret = sc.read(buffer)) > 0) {

					}
				} catch (IOException e) {

				} finally {
					buffer.flip();
				}
				if (readBytes > 0) {
					msg = Charset.forName("utf-8").decode(buffer).toString();
					buffer = null;
				}
			} finally {
				if (buffer != null)
					buffer.clear();
			}
			try {
				if (log.isDebugEnabled()) {
					log.debug("接收消息：" + msg + "，来源：" + sc.getRemoteAddress());
				}
				Thread.sleep(2000);
				sc.write(ByteBuffer.wrap((msg + " server response ").getBytes(Charset.forName("utf-8"))));
			} catch (Exception e) {
				log.error("写入响应消息异常", e);
			}
		}
	}

	public static void main(String[] args) {
		Server server = new Server();
		try {
			server.listen();
		} catch (IOException e) {

		}
	}
}
