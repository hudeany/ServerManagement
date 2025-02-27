package de.soderer.servermanagement.worker;

import de.soderer.servermanagement.ServerManagementItem;
import de.soderer.utilities.worker.WorkerParentSimple;
import de.soderer.utilities.worker.WorkerSimple;

public class RefreshServerItemStatusWorker extends WorkerSimple<Boolean> {
	protected ServerManagementItem serverManagementItem;

	public RefreshServerItemStatusWorker(final WorkerParentSimple parent, ServerManagementItem serverManagementItem) {
		super(parent);
		this.serverManagementItem = serverManagementItem;
	}

	@Override
	public Boolean work() throws Exception {
		itemsToDo = 1;
		itemsDone = 0;
		serverManagementItem.refreshStatus();
		itemsDone++;
		return true;
	}
}