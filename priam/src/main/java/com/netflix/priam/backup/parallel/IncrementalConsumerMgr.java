package com.netflix.priam.backup.parallel;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Singleton;
import com.netflix.priam.backup.AbstractBackupPath;
import com.netflix.priam.backup.IBackupFileSystem;
import com.netflix.priam.backup.IIncrementalBackup;
import com.netflix.priam.backup.AbstractBackupPath.BackupFileType;

/*
 * Monitors files to be uploaded and assigns each file to a worker
 */
public class IncrementalConsumerMgr implements Runnable {
	
	private static final Logger logger = LoggerFactory.getLogger(IncrementalConsumerMgr.class);
	
	private AtomicBoolean run = new AtomicBoolean(true);
	private ThreadPoolExecutor executor;
	private IBackupFileSystem fs;
	private ITaskQueueMgr<AbstractBackupPath> taskQueueMgr;
	BackupPostProcessingCallback<AbstractBackupPath> callback;

	
	public IncrementalConsumerMgr(ITaskQueueMgr<AbstractBackupPath> taskQueueMgr, IBackupFileSystem fs) {
		this.taskQueueMgr = taskQueueMgr;
		this.fs = fs;
		
		/*
		 * Too few threads, the queue will build up, consuming a lot of memory.
		 * Too many threads on the other hand will slow down the whole system due to excessive context switches - and lead to same symptoms.
		 */
		int maxWorkers = 5; //TODO: FP
		executor = new ThreadPoolExecutor(maxWorkers, maxWorkers, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>());
		
		callback = new IncrementalBkupPostProcessing(this.taskQueueMgr); 
	}

	/*
	 * Stop looking for files to upload
	 */
	public void shutdown() {
		this.run.set(false);
		this.executor.shutdown(); //will not accept new task and waits for active threads to be completed before shutdown.
	}
	
	@Override
	public void run() {
		while(this.run.get()) {
			
			logger.info("Size of work queue: " + this.taskQueueMgr.getNumOfTasksToBeProessed());
			
			while( this.taskQueueMgr.hasTasks() ) {
				try {
					AbstractBackupPath bp = this.taskQueueMgr.take();
					logger.info("Dequeued task: " + bp.getFileName());
					
					IncrementalConsumer task = new IncrementalConsumer(bp, this.fs, this.callback);
					executor.submit(task);

					
				} catch (InterruptedException e) {
					logger.warn("Was interrupted while wating to dequeued a task.  Msgl: " + e.getLocalizedMessage());
				}
			}
			
			//Lets not overwhelmend the node hence we will pause before checking the work queue again.
			try {
				Thread.currentThread().sleep(IIncrementalBackup.INCREMENTAL_INTERVAL_IN_MILLISECS);
			} catch (InterruptedException e) {
				logger.warn("Was interrupted while sleeping until next interval run.  Msgl: " + e.getLocalizedMessage());
			}
		}
		
	}

}