package de.soderer.servermanagement.worker;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import de.soderer.servermanagement.ServerManagementItem;
import de.soderer.utilities.worker.WorkerParentSimple;
import de.soderer.utilities.worker.WorkerSimple;

public class CheckAllServerStatusWorker extends WorkerSimple<List<String>> {
	protected List<ServerManagementItem> serverItems;
	
	public CheckAllServerStatusWorker(final WorkerParentSimple parent, List<ServerManagementItem> serverItems) {
		super(parent);
		this.serverItems = new ArrayList<>(serverItems);
	}

	@Override
	public List<String> work() throws Exception {
		List<String> errorneousServers = new ArrayList<>();
		
		itemsToDo = serverItems.size();
		itemsDone = 0;
		
		for (ServerManagementItem serverItem : serverItems) {
			serverItem.refreshStatusAsync();
		}
		
		boolean allDone = false;
		while (!allDone) {
			Thread.sleep(500);
			allDone = true;
			
			for (Iterator<ServerManagementItem> iter = serverItems.listIterator(); iter.hasNext(); ) {
				ServerManagementItem serverItem = iter.next();
				if (!serverItem.refreshStatusIsDone()) {
					allDone = false;
				} else {
					if ("ERROR".equals(serverItem.getStatus())) {
						errorneousServers.add(serverItem.getServerName());
					}
					iter.remove();
					itemsDone++;
					signalProgress(true);
				}
			}
		}
		
		return errorneousServers;
	}
}