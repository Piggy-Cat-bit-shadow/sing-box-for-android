package io.nekohasekai.sfa.compose.screen.dashboard.groups

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.sfa.compose.base.BaseViewModel
import io.nekohasekai.sfa.compose.base.ScreenEvent
import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.compose.model.GroupItem
import io.nekohasekai.sfa.compose.model.toList
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.ktx.unwrap
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.RemoteControlManager
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class GroupsUiState(
    val groups: List<Group> = emptyList(),
    val isLoading: Boolean = false,
    val expandedGroups: Set<String> = emptySet(),
    val testingGroups: Set<String> = emptySet(),
    val showCloseConnectionsSnackbar: Boolean = false,
)

sealed class GroupsEvent : ScreenEvent {
    data class GroupSelected(val groupTag: String, val itemTag: String) : GroupsEvent()
}

/**
 * The kernel's read-back for one selection, decoded from the JSON document
 * `CommandClient.SelectOutboundWithRequest` returns.
 *
 * A tag is not an attempt: two taps on one group share the group tag, so a late answer for the
 * first could settle the second. `requestId` names the attempt, `appliedOutboundTag` is what the
 * group actually holds afterwards (not what was asked for) and `appliedRevision` is the state the
 * next attempt must be judged against. `supported == false` is a capability answer from a core
 * that predates the identity fields - the selection was performed, but nobody can confirm it.
 */
@Serializable
private data class SelectOutboundReadBack(
    val supported: Boolean = false,
    val unconfirmed: Boolean = false,
    val requestId: Long = 0,
    val appliedOutboundTag: String? = null,
    val appliedRevision: Long = 0,
    val stale: Boolean = false,
)

class GroupsViewModel(private val sharedCommandClient: CommandClient? = null) :
    BaseViewModel<GroupsUiState, GroupsEvent>(),
    CommandClient.Handler {
    private val commandClient: CommandClient
    private val isUsingSharedClient: Boolean

    private val _serviceStatus = MutableStateFlow(Status.Stopped)
    val serviceStatus = _serviceStatus.asStateFlow()
    private var lastServiceStatus: Status = Status.Stopped

    /** The latest selection attempt per group, keyed by the request identity the kernel echoes. */
    private data class PendingSelection(val requestId: Long, val tag: String)

    private val selectionRequestCounter = AtomicLong(0)
    private val pendingSelections = mutableMapOf<String, PendingSelection>()
    private val appliedRevisions = mutableMapOf<String, Long>()
    private val selectionJson = Json { ignoreUnknownKeys = true }

    init {
        if (sharedCommandClient != null) {
            commandClient = sharedCommandClient
            isUsingSharedClient = true
            commandClient.addHandler(this)
        } else {
            commandClient =
                CommandClient(
                    viewModelScope,
                    CommandClient.ConnectionType.Groups,
                    this,
                )
            isUsingSharedClient = false
        }

        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                RemoteControlManager.remoteServer,
                RemoteControlManager.isConnected,
                _serviceStatus,
            ) { foreground, remoteServer, remoteConnected, status ->
                SessionTarget(
                    connect = foreground &&
                        if (remoteServer != null) remoteConnected else status == Status.Started,
                    remoteServerId = remoteServer?.id,
                )
            }.distinctUntilChanged().collect { target ->
                if (target.connect) {
                    if (isUsingSharedClient) {
                        commandClient.addHandler(this@GroupsViewModel)
                    } else {
                        updateState { copy(isLoading = true) }
                        commandClient.connect()
                    }
                } else {
                    if (isUsingSharedClient) {
                        commandClient.removeHandler(this@GroupsViewModel)
                    } else {
                        commandClient.disconnect()
                    }
                }
            }
        }
    }

    private data class SessionTarget(val connect: Boolean, val remoteServerId: Long?)

    override fun createInitialState() = GroupsUiState()

    override fun onCleared() {
        super.onCleared()
        if (isUsingSharedClient) {
            commandClient.removeHandler(this)
        } else {
            commandClient.disconnect()
        }
    }

    private fun handleServiceStatusChange(status: Status) {
        if (RemoteControlManager.remoteServer.value != null) {
            return
        }
        if (status != Status.Started) {
            updateState {
                copy(
                    groups = emptyList(),
                    isLoading = false,
                )
            }
        }
    }

    fun updateServiceStatus(status: Status) {
        if (status == lastServiceStatus) {
            return
        }
        lastServiceStatus = status
        viewModelScope.launch {
            _serviceStatus.emit(status)
            handleServiceStatusChange(status)
        }
    }

    fun toggleGroupExpand(groupTag: String) {
        val newExpanded = !uiState.value.expandedGroups.contains(groupTag)
        updateState {
            val newExpandedGroups = if (newExpanded) {
                expandedGroups + groupTag
            } else {
                expandedGroups - groupTag
            }
            copy(expandedGroups = newExpandedGroups)
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                CommandTarget.standaloneClient().setGroupExpand(groupTag, newExpanded)
            }
        }
    }

    fun toggleAllGroups() {
        val groups = uiState.value.groups
        val allCollapsed = uiState.value.expandedGroups.isEmpty()
        val newExpanded = allCollapsed

        updateState {
            if (allCollapsed) {
                copy(expandedGroups = groups.map { it.tag }.toSet())
            } else {
                copy(expandedGroups = emptySet())
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            groups.forEach { group ->
                runCatching {
                    CommandTarget.standaloneClient().setGroupExpand(group.tag, newExpanded)
                }
            }
        }
    }

    fun selectGroupItem(groupTag: String, itemTag: String) {
        // Check if this is actually a different selection
        val currentGroup = uiState.value.groups.find { it.tag == groupTag }
        if (currentGroup?.selected == itemTag) {
            // Same item selected, no need to do anything
            return
        }

        val requestId = selectionRequestCounter.incrementAndGet()
        val expectedRevision = appliedRevisions[groupTag] ?: 0L
        pendingSelections[groupTag] = PendingSelection(requestId, itemTag)
        // Optimistic, but only while the attempt is in flight: `settleSelection` removes the
        // pending entry, and `updateGroups` stops overlaying the moment it is gone.
        updateState {
            copy(
                groups =
                groups.map { group ->
                    if (group.tag == groupTag) group.copy(selected = itemTag) else group
                },
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val document =
                    CommandTarget.standaloneClient()
                        .selectOutboundWithRequest(groupTag, itemTag, requestId, expectedRevision)
                        .unwrap
                val readBack =
                    runCatching {
                        selectionJson.decodeFromString<SelectOutboundReadBack>(document)
                    }.getOrNull()
                withContext(Dispatchers.Main) {
                    settleSelection(groupTag, requestId, itemTag, readBack)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (pendingSelections[groupTag]?.requestId == requestId) {
                        pendingSelections.remove(groupTag)
                    }
                    sendError(e)
                }
            }
        }
    }

    /**
     * Applies the kernel's read-back for one attempt, if it still owns the row.
     *
     * The row follows `appliedOutboundTag` - what the group HOLDS - not what was requested: a hard
     * switch or a refusing group can end somewhere else. A late answer for a replaced attempt, and
     * an answer from a core that cannot confirm identity, change nothing.
     */
    private fun settleSelection(
        groupTag: String,
        requestId: Long,
        requestedTag: String,
        readBack: SelectOutboundReadBack?,
    ) {
        if (pendingSelections[groupTag]?.requestId != requestId) {
            return
        }
        pendingSelections.remove(groupTag)
        if (readBack == null || !readBack.supported || readBack.unconfirmed) {
            // Capability answer: the selection was sent, but this core cannot say which attempt it
            // was. The groups stream is the authority; no revision is invented here.
            return
        }
        val appliedTag = readBack.appliedOutboundTag ?: requestedTag
        appliedRevisions[groupTag] = readBack.appliedRevision
        updateState {
            copy(
                groups =
                groups.map { group ->
                    if (group.tag == groupTag) group.copy(selected = appliedTag) else group
                },
                showCloseConnectionsSnackbar = true,
            )
        }
        sendEvent(GroupsEvent.GroupSelected(groupTag, appliedTag))
    }

    fun closeConnections() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                CommandTarget.standaloneClient().closeConnections()
                withContext(Dispatchers.Main) {
                    dismissCloseConnectionsSnackbar()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dismissCloseConnectionsSnackbar()
                }
                sendError(e)
            }
        }
    }

    fun dismissCloseConnectionsSnackbar() {
        updateState {
            copy(showCloseConnectionsSnackbar = false)
        }
    }

    fun urlTest(outboundTag: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                CommandTarget.standaloneClient().urlTest(outboundTag)
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    fun urlTestGroup(groupTag: String) {
        updateState { copy(testingGroups = testingGroups + groupTag) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                CommandTarget.standaloneClient().urlTest(groupTag)
            } catch (e: Exception) {
                sendError(e)
            } finally {
                withContext(Dispatchers.Main) {
                    updateState { copy(testingGroups = testingGroups - groupTag) }
                }
            }
        }
    }

    // CommandClient.Handler implementation
    override fun onConnected() {
        viewModelScope.launch(Dispatchers.Main) {
            // Connection established, waiting for groups
        }
    }

    override fun onDisconnected() {
        viewModelScope.launch(Dispatchers.Main) {
            updateState {
                copy(
                    groups = emptyList(),
                    isLoading = false,
                )
            }
        }
    }

    override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
        viewModelScope.launch(Dispatchers.Default) {
            val currentGroups = uiState.value.groups
            val currentByTag = currentGroups.associateBy { it.tag }
            val mergedGroups = newGroups.map { goGroup ->
                val converted = Group(goGroup)
                val existing = currentByTag[converted.tag]
                if (existing == converted) existing else converted
            }

            withContext(Dispatchers.Main) {
                val pending = pendingSelections.toMap()
                updateState {
                    val initialExpandedGroups = if (expandedGroups.isEmpty() && currentGroups.isEmpty()) {
                        mergedGroups.filter { it.isExpand }.map { it.tag }.toSet()
                    } else {
                        expandedGroups
                    }
                    // The stream can arrive before the read-back and show the pre-selection
                    // member. Overlay only the attempts that are still in flight - a settled or
                    // failed attempt has no entry, so the stream is free to correct the row.
                    val withPending =
                        mergedGroups.map { group ->
                            pending[group.tag]?.let { selection ->
                                group.copy(selected = selection.tag)
                            } ?: group
                        }
                    copy(
                        groups = if (withPending == groups) groups else withPending,
                        expandedGroups = initialExpandedGroups,
                        isLoading = false,
                    )
                }
            }
        }
    }
}
