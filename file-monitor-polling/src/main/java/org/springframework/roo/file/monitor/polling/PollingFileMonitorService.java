package org.springframework.roo.file.monitor.polling;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Logger;

import org.springframework.roo.file.monitor.DirectoryMonitoringRequest;
import org.springframework.roo.file.monitor.FileMonitorService;
import org.springframework.roo.file.monitor.MonitoringRequest;
import org.springframework.roo.file.monitor.NotifiableFileMonitorService;
import org.springframework.roo.file.monitor.event.FileDetails;
import org.springframework.roo.file.monitor.event.FileEvent;
import org.springframework.roo.file.monitor.event.FileEventListener;
import org.springframework.roo.file.monitor.event.FileOperation;
import org.springframework.roo.support.lifecycle.ScopeDevelopment;
import org.springframework.roo.support.util.Assert;

/**
 * A simple polling-based {@link FileMonitorService}.
 * 
 * <p>
 * This implementation iterates over each of the {@link MonitoringRequest} instances,
 * building an active file index at the time of execution. It then compares this active file
 * index with the last time it was executed for that particular {@link MonitoringRequest}.
 * Events are then fired, and only when the event firing process has completed is the next
 * {@link MonitoringRequest} examined.
 * 
 * <p>
 * This implementation does not recognize {@link FileOperation#RENAMED} events. This implementation
 * will ignore any monitored files with a filename starting with a period (ie hidden files).
 * 
 * <p>
 * In the case of {@link FileOperation#DELETED} events, this implementation will present in the
 * {@link FileEvent#getLastModified()} times equal to the last time a deleted file was
 * modified. The time does NOT represent the deletion time nor the time the deletion was first
 * detected.
 * 
 * @author Ben Alex
 * @since 1.0
 *
 */
@ScopeDevelopment
public class PollingFileMonitorService implements NotifiableFileMonitorService {

	private static final Logger logger = Logger.getLogger(PollingFileMonitorService.class.getName());
	
	protected Set<FileEventListener> fileEventListeners = new CopyOnWriteArraySet<FileEventListener>();
	private Set<MonitoringRequest> requests = new CopyOnWriteArraySet<MonitoringRequest>();
	private Map<MonitoringRequest,Map<File,Long>> priorExecution =new WeakHashMap<MonitoringRequest,Map<File,Long>>();
	private Set<String> notifyChanged = new HashSet<String>();
	private Set<String> notifyCreated = new HashSet<String>();
	private Set<String> notifyDeleted = new HashSet<String>();
	
	// Mutex
	private Boolean lock = new Boolean(true);
	
	public List<FileDetails> getMonitored() {
		synchronized (lock) {
			List<FileDetails> monitored = new ArrayList<FileDetails>();
			if (requests.size() == 0) {
				return monitored;
			}

			for (MonitoringRequest request : requests) {
				if (priorExecution.containsKey(request)) {
					Map<File,Long> priorFiles = priorExecution.get(request);
					for (File priorFile : priorFiles.keySet()) {
						monitored.add(new FileDetails(priorFile, priorFiles.get(priorFile)));
					}
				}
			}
			
			return monitored;
		}
	}

	public boolean isDirty() {
		synchronized (lock) {
			return notifyChanged.size() > 0 || notifyCreated.size() > 0 || notifyDeleted.size() > 0;
		}
	}
	
	private boolean isWithin(MonitoringRequest request, String filePath) {
		String requestCanonicalPath;
		try {
			requestCanonicalPath = request.getFile().getCanonicalPath();
		} catch (IOException e) {
			return false;
		}
		if (request instanceof DirectoryMonitoringRequest) {
			DirectoryMonitoringRequest dmr = (DirectoryMonitoringRequest) request;
			if (dmr.isWatchSubtree()) {
				if (!filePath.startsWith(requestCanonicalPath)) {
					return false; // not within this directory or a sub-directory
				}
			} else {
				if (!FileDetails.matchesAntPath(requestCanonicalPath + File.separator + "*", filePath)) {
					return false; // not within this directory
				}
			}
		} else {
			if (!requestCanonicalPath.equals(filePath)) {
				return false; // not a file
			}
		}
		return true;
	}
	
	public int scanNotified() {
		synchronized (lock) {
			if (requests.size() == 0 || !isDirty()) {
				// There are no changes we can notify, so immediately return
				return 0;
			}
			
			int changes = 0;
				
			for (MonitoringRequest request : requests) {
				
				List<FileEvent> eventsToPublish = new ArrayList<FileEvent>();

				if (priorExecution.containsKey(request)) {
					// Need to perform a comparison, as we have data from a previous execution
					Map<File,Long> priorFiles = priorExecution.get(request);
					
					// Handle files apparently updated since last execution
					Set<String> toRemove = new HashSet<String>();
					for (String filePath : notifyChanged) {
						// Skip this notification if it doesn't fall under the present monitoring request
						if (!isWithin(request, filePath)) {
							continue;
						}
						// Record to remove this one, as we've definitely processed it
						toRemove.add(filePath);
						// Skip this file if it doesn't exist
						File thisFile = new File(filePath);
						if (!thisFile.exists()) {
							continue;
						}
						// Record the notification
						eventsToPublish.add(new FileEvent(new FileDetails(thisFile, thisFile.lastModified()), FileOperation.UPDATED, null));
						// Update the prior execution map so it isn't notified again next round
						priorFiles.put(thisFile, thisFile.lastModified());
						// Also remove it from the created list, if it's in there
						if (notifyCreated.contains(filePath)) {
							notifyCreated.remove(filePath);
						}
					}
					for (String remove : toRemove) {
						notifyChanged.remove(remove);
					}

					// Handle files apparently created since last execution
					toRemove = new HashSet<String>();
					for (String filePath : notifyCreated) {
						// Skip this notification if it doesn't fall under the present monitoring request
						if (!isWithin(request, filePath)) {
							continue;
						}
						// Record to remove this one, as we've definitely processed it
						toRemove.add(filePath);
						// Skip this file if it doesn't exist
						File thisFile = new File(filePath);
						if (!thisFile.exists()) {
							continue;
						}
						// Record the notification
						eventsToPublish.add(new FileEvent(new FileDetails(thisFile, thisFile.lastModified()), FileOperation.CREATED, null));
						// Update the prior execution map so it isn't notified again next round
						priorFiles.put(thisFile, thisFile.lastModified());
					}
					for (String remove : toRemove) {
						notifyCreated.remove(remove);
					}
					
					// Handle files apparently deleted since last execution
					toRemove = new HashSet<String>();
					for (String filePath : notifyDeleted) {
						// Skip this notification if it doesn't fall under the present monitoring request
						if (!isWithin(request, filePath)) {
							continue;
						}
						// Record to remove this one, as we've definitely processed it
						toRemove.add(filePath);
						// Skip this file if it suddenly exists again (it shouldn't be in the notify deleted in this case!)
						File thisFile = new File(filePath);
						if (thisFile.exists()) {
							continue;
						}
						// Record the notification
						eventsToPublish.add(new FileEvent(new FileDetails(thisFile, null), FileOperation.DELETED, null));
						// Update the prior execution map so it isn't notified again next round
						priorFiles.remove(thisFile);
					}
					for (String remove : toRemove) {
						notifyDeleted.remove(remove);
					}
				}
				
				publish(eventsToPublish);
				
				changes += eventsToPublish.size();
			}
			
			return changes;
		}
	}

	public int scanAll() {
		synchronized (lock) {
			if (requests.size() == 0) {
				return 0;
			}

			int changes = 0;

			for (MonitoringRequest request : requests) {

				boolean includeSubtree = false;
				if (request instanceof DirectoryMonitoringRequest) {
					includeSubtree = ((DirectoryMonitoringRequest)request).isWatchSubtree();
				}
				
				if (!request.getFile().exists()) {
					logger.warning("Cannot monitor non-existent path '" + request.getFile() + "'");
					continue;
				}
				
				// Build contents of the monitored location
				Map<File,Long> currentExecution = new HashMap<File,Long>();
				computeEntries(currentExecution, request.getFile(), includeSubtree);
				
				List<FileEvent> eventsToPublish = new ArrayList<FileEvent>();

				if (priorExecution.containsKey(request)) {
					// Need to perform a comparison, as we have data from a previous execution
					Map<File,Long> priorFiles = priorExecution.get(request);
					
					// Locate created and modified files
					for (File thisFile : currentExecution.keySet()) {
						
						if (!priorFiles.containsKey(thisFile)) {
							// This file did not exist last execution, so it must be new
							eventsToPublish.add(new FileEvent(new FileDetails(thisFile, currentExecution.get(thisFile)), FileOperation.CREATED, null));
							try {
								// If this file was already going to be notified, there is no need to do it twice
								notifyCreated.remove(thisFile.getCanonicalPath());
							} catch (IOException ignored) {}
							continue;
						} 
						
						Long currentTimestamp = currentExecution.get(thisFile);
						Long previousTimestamp = priorFiles.get(thisFile);
						if (!currentTimestamp.equals(previousTimestamp)) {
							// Modified
							eventsToPublish.add(new FileEvent(new FileDetails(thisFile, currentExecution.get(thisFile)), FileOperation.UPDATED, null));
							try {
								// If this file was already going to be notified, there is no need to do it twice
								notifyChanged.remove(thisFile.getCanonicalPath());
							} catch (IOException ignored) {}
							continue;
						}
					}
					
					// Now locate deleted files
					priorFiles.keySet().removeAll(currentExecution.keySet());
					for (File deletedFile : priorFiles.keySet()) {
						eventsToPublish.add(new FileEvent(new FileDetails(deletedFile, priorFiles.get(deletedFile)), FileOperation.DELETED, null));
						try {
							// If this file was already going to be notified, there is no need to do it twice
							notifyDeleted.remove(deletedFile.getCanonicalPath());
						} catch (IOException ignored) {}
						continue;
					}
				} else {
					// No data from previous execution, so it's a newly-monitored location
					for (File thisFile : currentExecution.keySet()) {
						eventsToPublish.add(new FileEvent(new FileDetails(thisFile, currentExecution.get(thisFile)), FileOperation.MONITORING_START, null));
					}
				}
				
				// Record the monitored location's contents, ready for next execution
				priorExecution.put(request, currentExecution);
				
				// We can discard the created and deleted notifications, as they would have been correctly discovered in the above loop
				notifyCreated.clear();
				notifyDeleted.clear();

				// Explicitly handle any undiscovered update notifications, as this indicates an identical millisecond update occurred
				for (String canonicalPath : notifyChanged) {
					File file = new File(canonicalPath);
					eventsToPublish.add(new FileEvent(new FileDetails(file, file.lastModified()), FileOperation.UPDATED, null));
				}
				notifyChanged.clear();
				
				publish(eventsToPublish);
				
				changes += eventsToPublish.size();
			}
			
			return changes;
		}
	}

	/**
	 * Publish the events, if needed
	 * 
	 * @param eventsToPublish to publish (not null, but can be empty)
	 */
	private void publish(List<FileEvent> eventsToPublish) {
		if (eventsToPublish.size() > 0) {
			for (FileEvent event : eventsToPublish) {
				for (FileEventListener listener : fileEventListeners) {
					listener.onFileEvent(event);
				}
			}
		}
	}
	
	/**
	 * Adds one or more entries into the Map. The key of the Map is the File object, and the value
	 * is the {@link File#lastModified()} time.
	 * 
	 * <p>
	 * Specifically:
	 * 
	 * <ul>
	 * <li>If invoked with a File that is actually a File, only the file is added.</li>
	 * <li>If invoked with a File that is actually a Directory, all files and directories are added.</li>
	 * <li>If invoked with a File that is actually a Directory, subdirectories will be added only if 
	 * "includeSubtree" is true.</li>
	 * </ul>
	 */
	private void computeEntries(Map<File, Long> map, File currentFile, boolean includeSubtree) {
		Assert.notNull(map, "Map required");
		Assert.notNull(currentFile, "Current file is required");

		if (currentFile.exists() == false || (currentFile.getName().length() > 1 && currentFile.getName().startsWith("."))) {
			return;
		}
		
		map.put(currentFile, currentFile.lastModified());

		if (currentFile.isDirectory()) {
			File[] files = currentFile.listFiles();
			if (files == null || files.length == 0) return;
			for (File file : files) {
				if (file.isFile() || includeSubtree) {
					computeEntries(map, file, includeSubtree);
				}
			}
		}
	}

	public boolean add(MonitoringRequest request) {
		synchronized (lock) {
			Assert.notNull(request, "MonitoringRequest required");
			
			// Ensure existing monitoring requests don't overlap with this new request;
			// amend existing requests or ignore new request as appropriate
			if (request instanceof DirectoryMonitoringRequest) {
				DirectoryMonitoringRequest dmr = (DirectoryMonitoringRequest) request;
				if (dmr.isWatchSubtree()) {
					for (MonitoringRequest existing : requests) {
						if (request instanceof DirectoryMonitoringRequest) {
							DirectoryMonitoringRequest existingDmr = (DirectoryMonitoringRequest) existing;
							if (existingDmr.isWatchSubtree()) {
								// We have a new request and an existing request, both for directories, and both which monitor sub-trees
								String existingDmrPath;
								String newDmrPath;
								try {
									existingDmrPath = existingDmr.getFile().getCanonicalPath();
									newDmrPath = dmr.getFile().getCanonicalPath();
								} catch (IOException ioe) {
									throw new IllegalStateException("Unable to resolve canonical name", ioe);
								}
								// If the new request is a sub-directory of the existing request, ignore the new request as it's unnecessary
								if (newDmrPath.startsWith(existingDmrPath)) {
									return false;
								}
								// If the existing request is a sub-directory of the new request, remove the existing request as this new request
								// will incorporate it
								if (existingDmrPath.startsWith(newDmrPath)) {
									remove(existing);
								}
							}
						}
					}
				}
			}
			
			boolean result;
			result = requests.add(request);
			
			return result;
		}
	}

	public boolean remove(MonitoringRequest request) {
		synchronized (lock) {
			Assert.notNull(request, "MonitoringRequest required");
			
			// Advise of the cessation to monitoring
			if (priorExecution.containsKey(request)) {
				List<FileEvent> eventsToPublish = new ArrayList<FileEvent>();

				Map<File,Long> priorFiles = priorExecution.get(request);
				for (File thisFile : priorFiles.keySet()) {
					eventsToPublish.add(new FileEvent(new FileDetails(thisFile, priorFiles.get(thisFile)), FileOperation.MONITORING_FINISH, null));
				}

				publish(eventsToPublish);
			}

			priorExecution.remove(request);
			
			boolean result;
			result = requests.remove(request);
			
			return result;
		}
	}

	public void addFileEventListener(FileEventListener fileEventListener) {
		synchronized (lock) {
			Assert.notNull(fileEventListener, "File event listener required");
			fileEventListeners.add(fileEventListener);
		}
	}

	public void removeFileEventListener(FileEventListener fileEventListener) {
		synchronized (lock) {
			Assert.notNull(fileEventListener, "File event listener required");
			fileEventListeners.remove(fileEventListener);
		}
	}

	public SortedSet<FileDetails> findMatchingAntPath(String antPath) {
		synchronized (lock) {
			Assert.hasText(antPath, "Ant path required");
			
			SortedSet<FileDetails> result = new TreeSet<FileDetails>();
			
			if (requests.size() == 0) {
				return result;
			}
		
			for (MonitoringRequest request : requests) {
				if (priorExecution.containsKey(request)) {
					Map<File,Long> priorFiles = priorExecution.get(request);
					for (File priorFile : priorFiles.keySet()) {
						FileDetails fd = new FileDetails(priorFile, priorFiles.get(priorFile));
						if (fd.matchesAntPath(antPath)) {
							result.add(fd);
						}
					}
				}
			}
			
			return result;
		}
	}

	public void notifyChanged(String fileCanoncialPath) {
		synchronized (lock) {
			notifyChanged.add(fileCanoncialPath);
		}
	}

	public void notifyCreated(String fileCanoncialPath) {
		synchronized (lock) {
			notifyCreated.add(fileCanoncialPath);
		}
	}

	public void notifyDeleted(String fileCanoncialPath) {
		synchronized (lock) {
			notifyDeleted.add(fileCanoncialPath);
		}
	}
	
}
