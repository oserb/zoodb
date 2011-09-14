package org.zoodb.jdo.stuff;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class FileLogger {

	private static final String DB_FILE_NAME = "zooFileLogger.log";
	private static final String DB_REP_PATH = 
		System.getProperty("user.home") + File.separator + "zoodb"; 

	private final PrintWriter out;

	public FileLogger() {
		this(DB_FILE_NAME);
	}

	public FileLogger(String fileName) {
		//create file
		try {
			FileWriter outFile = new FileWriter(DB_REP_PATH + File.separator + DB_FILE_NAME);
			out = new PrintWriter(outFile);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		//prepare closing file
		Runtime.getRuntime().addShutdownHook(new Thread() { 
			public void run() {
				if (out != null) {
					out.flush();
					out.close();
				}
			};
		} );
	}

	private int i = 0;

	public void write(String s) {
		out.append(s);
		if (i++ > 100) {
			out.flush();
			i = 0;
		}
		//		out.flush();
	}

	@Override
	protected void finalize() throws Throwable {
		if (out != null) {
			out.flush();
			out.close();
		}
		//super.finalize();
	}

}