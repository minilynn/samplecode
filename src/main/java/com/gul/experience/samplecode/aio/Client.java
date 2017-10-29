/**
 * 
 */
package com.gul.experience.samplecode.aio;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

/**
 *
 * @author Lynn
 *
 */
public class Client {
	private static String DEFAULT_HOST = "127.0.0.1";
	private static int DEFAULT_PORT = 12345;
	private static AsyncClientHandler clientHandle;

	public static void start() {
		start(DEFAULT_HOST, DEFAULT_PORT);
	}

	public static synchronized void start(String ip, int port) {
		if (clientHandle != null)
			return;
		clientHandle = new AsyncClientHandler(ip, port);
		new Thread(clientHandle, "Client").start();
	}

	// 向服务器发送消息
	public static boolean sendMsg(String msg) throws Exception {
		if (msg.equals("q"))
			return false;
		clientHandle.sendMsg(msg);
		return true;
	}

	@SuppressWarnings("resource")
	public static void main(String[] args) throws Exception {
		Client.start();
		System.out.println("请输入请求消息：");
		Scanner scanner = new Scanner(System.in);
		while (Client.sendMsg(scanner.nextLine()))
			;
	}

}

class AsyncClientHandler implements CompletionHandler<Void, AsyncClientHandler>, Runnable {
	private AsynchronousSocketChannel clientChannel;
	private String host;
	private int port;
	private CountDownLatch latch;

	public AsyncClientHandler(String host, int port) {
		this.host = host;
		this.port = port;
		try {
			// 创建异步的客户端通道
			clientChannel = AsynchronousSocketChannel.open();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void run() {
		// 创建CountDownLatch等待
		latch = new CountDownLatch(1);
		// 发起异步连接操作，回调参数就是这个类本身，如果连接成功会回调completed方法
		clientChannel.connect(new InetSocketAddress(host, port), this, this);
		try {
			latch.await();
		} catch (InterruptedException e1) {
			e1.printStackTrace();
		}
		try {
			clientChannel.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// 连接服务器成功
	// 意味着TCP三次握手完成
	@Override
	public void completed(Void result, AsyncClientHandler attachment) {
		System.out.println("客户端成功连接到服务器...");
	}

	// 连接服务器失败
	@Override
	public void failed(Throwable exc, AsyncClientHandler attachment) {
		System.err.println("连接服务器失败...");
		exc.printStackTrace();
		try {
			clientChannel.close();
			latch.countDown();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// 向服务器发送消息
	public void sendMsg(String msg) {
		byte[] req = msg.getBytes();
		ByteBuffer writeBuffer = ByteBuffer.allocate(req.length);
		writeBuffer.put(req);
		writeBuffer.flip();
		// 异步写
		clientChannel.write(writeBuffer, writeBuffer, new WriteHandler(clientChannel, latch));
	}
}

class WriteHandler implements CompletionHandler<Integer, ByteBuffer> {
	private AsynchronousSocketChannel clientChannel;
	private CountDownLatch latch;

	public WriteHandler(AsynchronousSocketChannel clientChannel, CountDownLatch latch) {
		this.clientChannel = clientChannel;
		this.latch = latch;
	}

	@Override
	public void completed(Integer result, ByteBuffer buffer) {
		// 完成全部数据的写入
		if (buffer.hasRemaining()) {
			clientChannel.write(buffer, buffer, this);
		} else {
			// 读取数据
			ByteBuffer readBuffer = ByteBuffer.allocate(1024);
			clientChannel.read(readBuffer, readBuffer, new ClientReadHandler(clientChannel, latch));
		}
	}

	@Override
	public void failed(Throwable exc, ByteBuffer attachment) {
		System.err.println("数据发送失败...");
		try {
			clientChannel.close();
			latch.countDown();
		} catch (IOException e) {
		}
	}
}

class ClientReadHandler implements CompletionHandler<Integer, ByteBuffer> {
	private AsynchronousSocketChannel clientChannel;
	private CountDownLatch latch;

	public ClientReadHandler(AsynchronousSocketChannel clientChannel, CountDownLatch latch) {
		this.clientChannel = clientChannel;
		this.latch = latch;
	}

	@Override
	public void completed(Integer result, ByteBuffer buffer) {
		buffer.flip();
		byte[] bytes = new byte[buffer.remaining()];
		buffer.get(bytes);
		String body;
		try {
			body = new String(bytes, "UTF-8");
			System.out.println("客户端收到结果:" + body);
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void failed(Throwable exc, ByteBuffer attachment) {
		System.err.println("数据读取失败...");
		try {
			clientChannel.close();
			latch.countDown();
		} catch (IOException e) {
		}
	}
}
