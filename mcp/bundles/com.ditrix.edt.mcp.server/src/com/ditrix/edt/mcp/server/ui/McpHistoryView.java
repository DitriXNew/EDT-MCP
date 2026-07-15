/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DateTime;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.plugin.AbstractUIPlugin;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.history.McpCallHistory;
import com.ditrix.edt.mcp.server.history.McpCallRecord;
import com.ditrix.edt.mcp.server.history.StatsAggregator;
import com.ditrix.edt.mcp.server.history.StatsAggregator.StatsResult;
import com.ditrix.edt.mcp.server.history.ToolStats;
import com.ditrix.edt.mcp.server.protocol.McpConstants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * In-EDT view over the MCP request/response history recorded at the transport choke
 * point ({@code McpProtocolHandler.processRequest}). It reads the shared
 * {@link McpCallHistory} ring exclusively through {@link McpCallHistory#snapshot()}
 * and never mutates a record.
 *
 * <p>The view has two tabs:</p>
 * <ul>
 *   <li><b>History</b> &mdash; a table (time / method-tool / duration / status) over a
 *       pretty-printed JSON detail pane, with a <i>Copy JSON</i> action.</li>
 *   <li><b>Statistics</b> &mdash; the per-tool context-usage rows produced by
 *       {@link StatsAggregator}, a run-wide summary, and the top-3 context consumers.</li>
 * </ul>
 *
 * <p>Every user-visible string is externalized through the package NLS bundle
 * ({@link Messages}) so the view follows the EDT locale (English default,
 * {@code messages_ru.properties} Russian), exactly like the settings form and the
 * Tags UI. The MCP tool surface (tool names/descriptions on the wire) is unaffected
 * and stays English.</p>
 *
 * <p><b>Threading.</b> The view subscribes to the ring as a
 * {@link McpCallHistory.HistoryListener}; {@code onRecord} runs on the producer
 * (transport) thread and only <em>schedules</em> a coalesced repaint via
 * {@link Display#asyncExec(Runnable)} &mdash; every widget touch happens on the UI
 * thread. The <i>Pause</i> toolbar toggle pauses only this view's refresh; recording
 * itself is governed by the MCP Server preferences and keeps running while paused.</p>
 *
 * <p>The view owns no disposable SWT resources: the detail font comes from JFace
 * (not disposed here) and status colours are system colours; the only lifecycle duty
 * is unregistering the history listener in {@link #dispose()}.</p>
 */
public class McpHistoryView extends ViewPart implements McpCallHistory.HistoryListener
{
    /** The view ID as declared in plugin.xml. */
    public static final String ID = "com.ditrix.edt.mcp.server.history.historyView"; //$NON-NLS-1$

    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault()); //$NON-NLS-1$

    // Response-envelope keys for the UI status classifier. This mirrors the
    // package-private StatsAggregator.isErrorResponse contract (a JSON-RPC top-level
    // error, or a tool result with isError:true / success:false); the aggregator's
    // method is not visible from this package, so the small classifier is duplicated
    // here for the Status column and MUST stay in sync with it.
    private static final String KEY_ERROR = "error"; //$NON-NLS-1$
    private static final String KEY_RESULT = "result"; //$NON-NLS-1$
    private static final String KEY_IS_ERROR = "isError"; //$NON-NLS-1$
    private static final String KEY_STRUCTURED_CONTENT = "structuredContent"; //$NON-NLS-1$
    private static final String KEY_SUCCESS = "success"; //$NON-NLS-1$

    private final McpCallHistory history = McpCallHistory.getInstance();

    /** Pretty-printer for the JSON detail pane (own instance, HTML-escaping off). */
    private final Gson prettyGson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Coalesces a burst of producer-thread records into a single UI refresh. */
    private final AtomicBoolean refreshScheduled = new AtomicBoolean(false);

    /**
     * Stable backing list for the history table. Kept as the viewer's fixed input so
     * a live refresh mutates it in place and {@link TableViewer#refresh()} can
     * preserve the user's row selection (and thus the detail pane) by identity.
     */
    private final List<McpCallRecord> tableRows = new ArrayList<>();

    // History tab
    private TableViewer historyViewer;
    private Text detailText;
    private Button copyJsonButton;

    /**
     * The clipboard payload for the currently selected exchange: a single, VALID
     * pretty-printed JSON object {@code {"request": ..., "response": ...}} (each body
     * parsed back to JSON, or kept as a string value when it is truncated/non-JSON).
     * Copy-JSON copies exactly this. The detail Text control shows a separate,
     * human-readable two-section rendering ({@link #buildDisplayText}); the two are kept
     * distinct so the pane can carry section headers while the clipboard stays valid JSON.
     */
    private String detailJson = ""; //$NON-NLS-1$

    // History filter bar (all touched only on the UI thread).
    private Text methodFilterText;
    private Button errorsOnlyCheck;
    private Spinner minDurationSpinner;
    private Button intervalCheck;
    private DateTime fromDate;
    private DateTime fromTime;
    private DateTime toDate;
    private DateTime toTime;

    // Statistics tab
    private CTabFolder tabFolder;
    private CTabItem statsTab;
    private TableViewer statsViewer;
    private Label statsSummaryLabel;
    private Label statsTopLabel;

    // Toolbar state. 'paused' is read from the producer thread, so it is volatile;
    // the others are touched only on the UI thread.
    private volatile boolean paused;
    private boolean toolsCallOnly;
    private boolean autoScroll = true;

    @Override
    public void createPartControl(Composite parent)
    {
        parent.setLayout(new FillLayout());

        tabFolder = new CTabFolder(parent, SWT.BORDER);

        CTabItem historyTab = new CTabItem(tabFolder, SWT.NONE);
        historyTab.setText(Messages.McpHistoryView_TabHistory);
        historyTab.setControl(createHistoryTab(tabFolder));

        statsTab = new CTabItem(tabFolder, SWT.NONE);
        statsTab.setText(Messages.McpHistoryView_TabStatistics);
        statsTab.setControl(createStatisticsTab(tabFolder));

        tabFolder.setSelection(historyTab);
        tabFolder.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                if (tabFolder.getSelection() == statsTab)
                {
                    refreshStatistics();
                }
            }
        });

        createToolbar();

        history.addListener(this);
        refreshAll();
    }

    // ---------------------------------------------------------------- History tab

    private Composite createHistoryTab(Composite parent)
    {
        Composite container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 0;
        container.setLayout(layout);

        createFilterBar(container);

        SashForm sash = new SashForm(container, SWT.VERTICAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createHistoryTable(sash);
        createDetailPane(sash);

        sash.setWeights(60, 40);
        return container;
    }

    /**
     * A wrapping filter bar above the history table. All predicates it drives are
     * evaluated in {@link #refreshHistoryTable()} via {@link #matchesFilters}; the
     * Statistics tab is unaffected (it always reads the full snapshot). Every control
     * schedules a table-only refresh, never a modal, and is disposed with the view.
     */
    private void createFilterBar(Composite parent)
    {
        Composite bar = new Composite(parent, SWT.NONE);
        RowLayout barLayout = new RowLayout(SWT.HORIZONTAL);
        barLayout.center = true;
        barLayout.wrap = true;
        barLayout.marginTop = 2;
        barLayout.marginBottom = 2;
        barLayout.marginLeft = 3;
        bar.setLayout(barLayout);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        SelectionAdapter refreshOnChange = new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                refreshHistoryTable();
            }
        };

        Label methodLabel = new Label(bar, SWT.NONE);
        methodLabel.setText(Messages.McpHistoryView_FilterMethodLabel);

        methodFilterText = new Text(bar, SWT.BORDER | SWT.SEARCH);
        methodFilterText.setToolTipText(Messages.McpHistoryView_FilterMethodTooltip);
        methodFilterText.setMessage(Messages.McpHistoryView_FilterMethodPlaceholder);
        methodFilterText.setLayoutData(new RowData(160, SWT.DEFAULT));
        methodFilterText.addModifyListener(e -> refreshHistoryTable());

        errorsOnlyCheck = new Button(bar, SWT.CHECK);
        errorsOnlyCheck.setText(Messages.McpHistoryView_FilterErrorsOnlyLabel);
        errorsOnlyCheck.setToolTipText(Messages.McpHistoryView_FilterErrorsOnlyTooltip);
        errorsOnlyCheck.addSelectionListener(refreshOnChange);

        Label durationLabel = new Label(bar, SWT.NONE);
        durationLabel.setText(Messages.McpHistoryView_FilterMinDurationLabel);

        minDurationSpinner = new Spinner(bar, SWT.BORDER);
        minDurationSpinner.setMinimum(0);
        minDurationSpinner.setMaximum(Integer.MAX_VALUE);
        minDurationSpinner.setIncrement(50);
        minDurationSpinner.setPageIncrement(500);
        minDurationSpinner.setSelection(0);
        minDurationSpinner.setToolTipText(Messages.McpHistoryView_FilterMinDurationTooltip);
        minDurationSpinner.addSelectionListener(refreshOnChange);

        intervalCheck = new Button(bar, SWT.CHECK);
        intervalCheck.setText(Messages.McpHistoryView_FilterIntervalLabel);
        intervalCheck.setToolTipText(Messages.McpHistoryView_FilterIntervalTooltip);
        intervalCheck.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                setIntervalControlsEnabled(intervalCheck.getSelection());
                refreshHistoryTable();
            }
        });

        fromDate = new DateTime(bar, SWT.DATE | SWT.DROP_DOWN | SWT.BORDER);
        fromDate.addSelectionListener(refreshOnChange);
        fromTime = new DateTime(bar, SWT.TIME | SWT.BORDER);
        fromTime.setHours(0);
        fromTime.setMinutes(0);
        fromTime.setSeconds(0);
        fromTime.addSelectionListener(refreshOnChange);

        Label toLabel = new Label(bar, SWT.NONE);
        toLabel.setText(Messages.McpHistoryView_FilterIntervalTo);

        toDate = new DateTime(bar, SWT.DATE | SWT.DROP_DOWN | SWT.BORDER);
        toDate.addSelectionListener(refreshOnChange);
        toTime = new DateTime(bar, SWT.TIME | SWT.BORDER);
        toTime.setHours(23);
        toTime.setMinutes(59);
        toTime.setSeconds(59);
        toTime.addSelectionListener(refreshOnChange);

        // Interval filter defaults OFF -> the DateTime controls start disabled.
        setIntervalControlsEnabled(false);
    }

    private void setIntervalControlsEnabled(boolean enabled)
    {
        for (DateTime control : new DateTime[] {fromDate, fromTime, toDate, toTime})
        {
            if (control != null && !control.isDisposed())
            {
                control.setEnabled(enabled);
            }
        }
    }

    private void createHistoryTable(Composite parent)
    {
        Table table = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.SINGLE);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        historyViewer = new TableViewer(table);
        historyViewer.setContentProvider(ArrayContentProvider.getInstance());

        addHistoryColumn(Messages.McpHistoryView_ColumnTime, 160, new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return element instanceof McpCallRecord r
                    ? TIME_FORMAT.format(Instant.ofEpochMilli(r.getTimestampMs())) : ""; //$NON-NLS-1$
            }
        });

        addHistoryColumn(Messages.McpHistoryView_ColumnMethodTool, 260, new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return element instanceof McpCallRecord r ? methodToolLabel(r) : ""; //$NON-NLS-1$
            }
        });

        addHistoryColumn(Messages.McpHistoryView_ColumnDuration, 90, new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return element instanceof McpCallRecord r ? r.getDurationMs() + " ms" : ""; //$NON-NLS-1$ //$NON-NLS-2$
            }
        });

        addHistoryColumn(Messages.McpHistoryView_ColumnStatus, 80, new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof McpCallRecord r)
                {
                    return isError(r)
                        ? Messages.McpHistoryView_StatusError : Messages.McpHistoryView_StatusOk;
                }
                return ""; //$NON-NLS-1$
            }

            @Override
            public Color getForeground(Object element)
            {
                if (element instanceof McpCallRecord r && isError(r))
                {
                    return table.getDisplay().getSystemColor(SWT.COLOR_RED);
                }
                return null;
            }
        });

        historyViewer.addSelectionChangedListener(event -> updateDetail());
        historyViewer.setInput(tableRows);
    }

    private void addHistoryColumn(String header, int width, ColumnLabelProvider provider)
    {
        TableViewerColumn column = new TableViewerColumn(historyViewer, SWT.NONE);
        column.getColumn().setText(header);
        column.getColumn().setWidth(width);
        column.setLabelProvider(provider);
    }

    private void createDetailPane(Composite parent)
    {
        Composite composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 3;
        layout.marginHeight = 3;
        composite.setLayout(layout);

        Label header = new Label(composite, SWT.NONE);
        header.setText(Messages.McpHistoryView_DetailHeader);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        copyJsonButton = new Button(composite, SWT.PUSH);
        copyJsonButton.setText(Messages.McpHistoryView_CopyJson);
        copyJsonButton.setToolTipText(Messages.McpHistoryView_CopyJsonTooltip);
        copyJsonButton.setEnabled(false);
        copyJsonButton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                copyDetailToClipboard();
            }
        });

        detailText = new Text(composite,
            SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        detailText.setFont(JFaceResources.getTextFont());
        GridData textData = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
        detailText.setLayoutData(textData);
    }

    // ------------------------------------------------------------- Statistics tab

    private Composite createStatisticsTab(Composite parent)
    {
        Composite composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 5;
        layout.marginHeight = 5;
        composite.setLayout(layout);

        statsSummaryLabel = new Label(composite, SWT.WRAP);
        statsSummaryLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        statsTopLabel = new Label(composite, SWT.WRAP);
        statsTopLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label separator = new Label(composite, SWT.HORIZONTAL | SWT.SEPARATOR);
        separator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Table table = new Table(composite, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        statsViewer = new TableViewer(table);
        statsViewer.setContentProvider(ArrayContentProvider.getInstance());

        addStatsColumn(Messages.McpHistoryView_StatsToolMethod, SWT.LEFT, 240, ToolStats::getToolName);
        addStatsColumn(Messages.McpHistoryView_StatsCalls, SWT.RIGHT, 70, ts -> Long.toString(ts.getCalls()));
        addStatsColumn(Messages.McpHistoryView_StatsShare, SWT.RIGHT, 70,
            ts -> String.format("%.1f%%", ts.getSharePercent())); //$NON-NLS-1$
        addStatsColumn(Messages.McpHistoryView_StatsAvg, SWT.RIGHT, 80, ts -> ts.getAvgDurationMs() + " ms"); //$NON-NLS-1$
        addStatsColumn(Messages.McpHistoryView_StatsReqChars, SWT.RIGHT, 90,
            ts -> Long.toString(ts.getRequestChars()));
        addStatsColumn(Messages.McpHistoryView_StatsRespChars, SWT.RIGHT, 90,
            ts -> Long.toString(ts.getResponseChars()));
        addStatsColumn(Messages.McpHistoryView_StatsTokens, SWT.RIGHT, 80, ToolStats::getApproxTokensDisplay);
        addStatsColumn(Messages.McpHistoryView_StatsErrors, SWT.RIGHT, 70, ts -> Long.toString(ts.getErrorCount()));
        addStatsColumn(Messages.McpHistoryView_StatsContextWeight, SWT.RIGHT, 110,
            ts -> Long.toString(ts.getContextWeight()));

        return composite;
    }

    private void addStatsColumn(String header, int style, int width,
        java.util.function.Function<ToolStats, String> textFn)
    {
        TableViewerColumn column = new TableViewerColumn(statsViewer, style);
        column.getColumn().setText(header);
        column.getColumn().setWidth(width);
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return element instanceof ToolStats ts ? textFn.apply(ts) : ""; //$NON-NLS-1$
            }
        });
    }

    // ----------------------------------------------------------------- Toolbar

    private void createToolbar()
    {
        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();

        Action pauseAction = new Action(Messages.McpHistoryView_PauseAction, IAction.AS_CHECK_BOX)
        {
            @Override
            public void run()
            {
                paused = isChecked();
                if (!paused)
                {
                    refreshAll();
                }
                updateStatusLine();
            }
        };
        pauseAction.setToolTipText(Messages.McpHistoryView_PauseTooltip);
        pauseAction.setImageDescriptor(pluginImage("icons/stop.png")); //$NON-NLS-1$

        Action clearAction = new Action(Messages.McpHistoryView_ClearAction)
        {
            @Override
            public void run()
            {
                history.clear();
                refreshAll();
            }
        };
        clearAction.setToolTipText(Messages.McpHistoryView_ClearTooltip);
        clearAction.setImageDescriptor(sharedImage(ISharedImages.IMG_ELCL_REMOVEALL));

        Action filterAction = new Action(Messages.McpHistoryView_FilterAction, IAction.AS_CHECK_BOX)
        {
            @Override
            public void run()
            {
                toolsCallOnly = isChecked();
                refreshHistoryTable();
            }
        };
        filterAction.setToolTipText(Messages.McpHistoryView_FilterTooltip);

        Action autoScrollAction = new Action(Messages.McpHistoryView_AutoScrollAction, IAction.AS_CHECK_BOX)
        {
            @Override
            public void run()
            {
                autoScroll = isChecked();
                if (autoScroll)
                {
                    revealLatest();
                }
            }
        };
        autoScrollAction.setToolTipText(Messages.McpHistoryView_AutoScrollTooltip);
        autoScrollAction.setChecked(true);
        autoScrollAction.setImageDescriptor(sharedImage(ISharedImages.IMG_ELCL_SYNCED));

        toolbar.add(pauseAction);
        toolbar.add(autoScrollAction);
        toolbar.add(new Separator());
        toolbar.add(filterAction);
        toolbar.add(clearAction);
    }

    // ------------------------------------------------------------------ Refresh

    /**
     * Producer-thread callback: never touches a widget directly, only schedules a
     * coalesced UI refresh (skipped entirely while the view is paused).
     */
    @Override
    public void onRecord(McpCallRecord record)
    {
        scheduleRefresh();
    }

    /**
     * Recorder-config-change callback (recording toggled or ring resized via the MCP
     * Server preferences). Runs on the caller thread, so it only marshals the refresh
     * onto the UI thread. The status line is updated even while the view is paused (so a
     * "Recording: off" toggle shows at once); the table and statistics are refreshed only
     * when not paused (a shrunk ring may have evicted rows the view still shows).
     */
    @Override
    public void onConfigChanged()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            return;
        }
        display.asyncExec(() ->
        {
            if (historyViewer == null || historyViewer.getControl().isDisposed())
            {
                return;
            }
            if (paused)
            {
                updateStatusLine();
            }
            else
            {
                refreshAll();
            }
        });
    }

    private void scheduleRefresh()
    {
        if (paused)
        {
            return;
        }
        if (!refreshScheduled.compareAndSet(false, true))
        {
            return;
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            refreshScheduled.set(false);
            return;
        }
        display.asyncExec(() ->
        {
            refreshScheduled.set(false);
            if (!paused)
            {
                refreshAll();
            }
        });
    }

    private void refreshAll()
    {
        refreshHistoryTable();
        if (tabFolder != null && !tabFolder.isDisposed() && tabFolder.getSelection() == statsTab)
        {
            refreshStatistics();
        }
        updateStatusLine();
    }

    /**
     * Reads the current filter-bar state and returns the ring snapshot filtered by the
     * same AND predicate the history table uses ({@link #matchesFilters}). Shared by the
     * history table and the Statistics tab so the statistics reflect exactly the selected
     * tool/status/duration/time-window slice, not the whole ring. UI-thread only.
     *
     * @return the filtered records, oldest first
     */
    private List<McpCallRecord> collectFilteredRecords()
    {
        List<McpCallRecord> snapshot = history.snapshot();

        String textFilter = methodFilterText != null && !methodFilterText.isDisposed()
            ? methodFilterText.getText() : null;
        boolean errorsOnly = errorsOnlyCheck != null && !errorsOnlyCheck.isDisposed()
            && errorsOnlyCheck.getSelection();
        long minDurationMs = minDurationSpinner != null && !minDurationSpinner.isDisposed()
            ? minDurationSpinner.getSelection() : 0L;
        boolean intervalOn = intervalCheck != null && !intervalCheck.isDisposed()
            && intervalCheck.getSelection();
        long fromMs = Long.MIN_VALUE;
        long toMs = Long.MAX_VALUE;
        if (intervalOn)
        {
            fromMs = toEpochMillis(fromDate, fromTime, false);
            toMs = toEpochMillis(toDate, toTime, true);
        }

        List<McpCallRecord> filtered = new ArrayList<>();
        for (McpCallRecord record : snapshot)
        {
            if (matchesFilters(record, toolsCallOnly, textFilter, errorsOnly, minDurationMs, intervalOn,
                fromMs, toMs))
            {
                filtered.add(record);
            }
        }
        return filtered;
    }

    private void refreshHistoryTable()
    {
        if (historyViewer == null || historyViewer.getControl().isDisposed())
        {
            return;
        }
        tableRows.clear();
        tableRows.addAll(collectFilteredRecords());
        // refresh() (not setInput) so the current row selection survives a live update.
        historyViewer.refresh();
        if (autoScroll)
        {
            revealLatest();
        }
        updateDetail();
    }

    private void refreshStatistics()
    {
        if (statsViewer == null || statsViewer.getControl().isDisposed())
        {
            return;
        }
        // Aggregate the SAME filtered slice the history table shows, so the summary, rows
        // and top-3 reflect the selected tool/status/duration/time-window filters.
        StatsResult result = StatsAggregator.aggregate(collectFilteredRecords());
        statsViewer.setInput(result.getRows());
        statsSummaryLabel.setText(buildSummary(result));
        statsTopLabel.setText(buildTop3(result));
        statsSummaryLabel.getParent().layout();
    }

    private void revealLatest()
    {
        if (historyViewer == null || historyViewer.getControl().isDisposed())
        {
            return;
        }
        Table table = historyViewer.getTable();
        int count = table.getItemCount();
        if (count > 0)
        {
            table.showItem(table.getItem(count - 1));
        }
    }

    private void updateStatusLine()
    {
        String recording = history.isRecordingEnabled()
            ? Messages.McpHistoryView_RecordingOn : Messages.McpHistoryView_RecordingOff;
        String updates = paused ? Messages.McpHistoryView_UpdatesPaused : Messages.McpHistoryView_UpdatesLive;
        setContentDescription(NLS.bind(Messages.McpHistoryView_ContentDescription,
            new Object[] {recording, updates, Integer.valueOf(history.size())}));
    }

    // ------------------------------------------------------------- Detail pane

    private void updateDetail()
    {
        if (detailText == null || detailText.isDisposed())
        {
            return;
        }
        McpCallRecord record = selectedRecord();
        if (record == null)
        {
            if (!detailJson.isEmpty())
            {
                detailJson = ""; //$NON-NLS-1$
                detailText.setText(""); //$NON-NLS-1$
            }
            copyJsonButton.setEnabled(false);
            return;
        }
        copyJsonButton.setEnabled(true);
        String clipboardJson = buildClipboardJson(record);
        // Refresh only when the shown exchange actually changed, so typing into the
        // method filter (which re-runs refreshHistoryTable but keeps the selection)
        // does not reset the detail pane's scroll/caret to the top on every keystroke.
        if (clipboardJson.equals(detailJson))
        {
            return;
        }
        detailJson = clipboardJson;
        setDetailText(buildDisplayText(record));
    }

    private McpCallRecord selectedRecord()
    {
        IStructuredSelection selection = historyViewer.getStructuredSelection();
        if (!selection.isEmpty() && selection.getFirstElement() instanceof McpCallRecord record)
        {
            return record;
        }
        return null;
    }

    /**
     * The human-readable detail-pane rendering of one exchange: a "Request" section and a
     * "Response" section, each pretty-printed. This is display-only (it carries section
     * headers and is NOT valid JSON); the clipboard uses {@link #buildClipboardJson}.
     */
    private String buildDisplayText(McpCallRecord record)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(Messages.McpHistoryView_RequestSection).append('\n');
        sb.append(prettyOrNone(record.getRequestJson()));
        sb.append("\n\n").append(Messages.McpHistoryView_ResponseSection).append('\n'); //$NON-NLS-1$
        sb.append(prettyOrNone(record.getResponseJson()));
        return sb.toString();
    }

    /**
     * Builds the Copy-JSON clipboard payload: ONE valid, pretty-printed JSON object
     * {@code {"request": <req>, "response": <resp>}}. Each body is parsed back to a JSON
     * value so structured payloads stay structured; a {@code null} body becomes JSON
     * {@code null}, and a truncated / non-JSON body is kept as a JSON string value — so
     * the result is always valid JSON, unlike the two-section display text.
     */
    private String buildClipboardJson(McpCallRecord record)
    {
        JsonObject root = new JsonObject();
        root.add("request", asJsonOrString(record.getRequestJson())); //$NON-NLS-1$
        root.add("response", asJsonOrString(record.getResponseJson())); //$NON-NLS-1$
        return prettyGson.toJson(root);
    }

    /**
     * Parses {@code json} to a JSON element, or wraps it as a JSON string value when it is
     * {@code null} (→ JSON null) or not parseable (e.g. a body carrying the truncation
     * marker), so the enclosing object is always valid JSON.
     */
    private static JsonElement asJsonOrString(String json)
    {
        if (json == null)
        {
            return JsonNull.INSTANCE;
        }
        try
        {
            return JsonParser.parseString(json);
        }
        catch (RuntimeException e) // NOSONAR a non-JSON / truncated body is kept as a string value
        {
            return new JsonPrimitive(json);
        }
    }

    private String prettyOrNone(String json)
    {
        String pretty = prettyJson(json);
        return pretty.isEmpty() ? Messages.McpHistoryView_DetailNone : pretty;
    }

    /**
     * Pretty-prints a JSON payload with the view's own Gson; a {@code null}/blank
     * payload yields an empty string and any non-JSON text is returned verbatim.
     */
    private String prettyJson(String json)
    {
        if (json == null || json.isBlank())
        {
            return ""; //$NON-NLS-1$
        }
        try
        {
            JsonElement element = JsonParser.parseString(json);
            return prettyGson.toJson(element);
        }
        catch (RuntimeException e) // NOSONAR non-JSON payloads are shown verbatim, never thrown
        {
            return json;
        }
    }

    private void setDetailText(String text)
    {
        // Display-only rendering. First turn JSON string-value escaped newlines
        // (\r\n, \n) into real line breaks so multi-line values (e.g. a BSL module
        // body) read naturally instead of showing literal "\n"; then normalise every
        // newline (Gson emits '\n' between tokens) to the platform delimiter so the
        // Text control on every OS renders breaks instead of control glyphs. The
        // Copy-JSON clipboard keeps the untouched, still-valid JSON (escapes intact).
        String rendered = renderEscapedNewlines(text);
        detailText.setText(rendered.replace("\n", Text.DELIMITER)); //$NON-NLS-1$
    }

    /**
     * Display-only transform: replaces JSON string-value escaped newline sequences
     * ({@code \r\n} then {@code \n}) with real line breaks so multi-line values read
     * naturally. Escaped backslashes ({@code \\}) are protected first, so a
     * JSON-encoded backslash immediately followed by {@code n}/{@code r} (e.g. a
     * Windows path serialized as {@code "C:\\node"}) is left intact instead of being
     * mis-rendered as a spurious line break. Never used for the clipboard, so the
     * copied text stays valid JSON.
     */
    static String renderEscapedNewlines(String text)
    {
        // A JSON-encoded escaped backslash ("\\") must not be read as the start of a
        // newline escape. Park every escaped backslash on a sentinel (the NUL char,
        // which pretty-printed JSON always emits as an escape, never literally),
        // unescape the genuine \r\n / \n sequences, then restore the escaped backslashes.
        final String sentinel = "\0"; //$NON-NLS-1$
        return text.replace("\\\\", sentinel) //$NON-NLS-1$
            .replace("\\r\\n", "\n") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\\n", "\n") //$NON-NLS-1$ //$NON-NLS-2$
            .replace(sentinel, "\\\\"); //$NON-NLS-1$
    }

    private void copyDetailToClipboard()
    {
        if (detailJson == null || detailJson.isEmpty())
        {
            return;
        }
        Clipboard clipboard = new Clipboard(detailText.getDisplay());
        try
        {
            clipboard.setContents(new Object[] {detailJson}, new Transfer[] {TextTransfer.getInstance()});
        }
        finally
        {
            clipboard.dispose();
        }
    }

    // ------------------------------------------------------------- Formatting

    private static String methodToolLabel(McpCallRecord record)
    {
        String method = record.getMethod() != null ? record.getMethod() : ""; //$NON-NLS-1$
        String tool = record.getToolName();
        if (tool != null && !tool.isBlank())
        {
            return method.isEmpty() ? tool : method + " : " + tool; //$NON-NLS-1$
        }
        return method;
    }

    /**
     * The filter grouping key for a record: the tool name for a {@code tools/call}
     * with a non-blank tool, otherwise the JSON-RPC method. This mirrors the
     * package-private {@code StatsAggregator.keyOf} (which is not visible from this
     * package) so the method/tool substring filter matches the same key the
     * Statistics rows are grouped by; the two MUST stay in sync. A record with no
     * method at all yields {@link StatsAggregator#NON_TOOL_METHODS_KEY}, exactly as
     * {@code keyOf} does.
     *
     * @param record the record to key (never {@code null})
     * @return the grouping key, never {@code null}
     */
    static String statKey(McpCallRecord record)
    {
        // Delegates to the single source of truth so the substring filter's key and
        // the Statistics rows can never drift apart.
        return StatsAggregator.keyOf(record);
    }

    /**
     * UI error classifier for a whole record: {@code true} when its recorded response
     * is an error outcome per {@link #isErrorResponse(String)}. A {@code null} record
     * is not an error. Shared by the Status column and the errors-only filter so the
     * two can never disagree.
     *
     * @param record the record to classify (may be {@code null})
     * @return {@code true} when the record's response represents an error outcome
     */
    static boolean isError(McpCallRecord record)
    {
        return record != null && isErrorResponse(record.getResponseJson());
    }

    /**
     * Pure AND-composed predicate for one history row. Combines, in order: the
     * existing {@code tools/call}-only toggle, the case-insensitive method/tool
     * substring, the errors-only toggle, the minimum-duration threshold, and (only
     * when {@code intervalOn}) the inclusive timestamp window. Extracted as a static
     * so it is unit-testable without SWT.
     *
     * @param record the record to test; {@code null} never matches
     * @param toolsCallOnly when {@code true}, keep only {@code tools/call} exchanges
     * @param textFilter a case-insensitive substring the record's
     *            {@link #statKey(McpCallRecord)} must contain; {@code null}/blank means
     *            no text filter
     * @param errorsOnly when {@code true}, keep only records classified as an error
     *            outcome by {@link #isError(McpCallRecord)}
     * @param minDurationMs keep only records with {@code getDurationMs() >=} this
     * @param intervalOn when {@code true}, apply the {@code [fromMs, toMs]} window
     * @param fromMsInclusive lower timestamp bound, inclusive (used iff {@code intervalOn})
     * @param toMsInclusive upper timestamp bound, inclusive (used iff {@code intervalOn})
     * @return {@code true} when the record passes every active filter
     */
    static boolean matchesFilters(McpCallRecord record, boolean toolsCallOnly, String textFilter,
        boolean errorsOnly, long minDurationMs, boolean intervalOn, long fromMsInclusive, long toMsInclusive)
    {
        if (record == null)
        {
            return false;
        }
        if (toolsCallOnly && !McpConstants.METHOD_TOOLS_CALL.equals(record.getMethod()))
        {
            return false;
        }
        if (textFilter != null && !textFilter.isBlank()
            && !statKey(record).toLowerCase(Locale.ROOT).contains(textFilter.toLowerCase(Locale.ROOT)))
        {
            return false;
        }
        if (errorsOnly && !isError(record))
        {
            return false;
        }
        if (record.getDurationMs() < minDurationMs)
        {
            return false;
        }
        if (intervalOn)
        {
            long ts = record.getTimestampMs();
            if (ts < fromMsInclusive || ts > toMsInclusive)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Combines a {@link SWT#DATE} widget and a {@link SWT#TIME} widget into an
     * epoch-millisecond instant in the system default time zone. The {@code endOfSecond}
     * flag rounds the millisecond field up to {@code 999} so an inclusive upper bound
     * covers the whole selected second (the widgets have second resolution).
     */
    private static long toEpochMillis(DateTime date, DateTime time, boolean endOfSecond)
    {
        Calendar cal = Calendar.getInstance();
        cal.clear();
        // DateTime.getMonth() is 0-based, matching Calendar's month field.
        cal.set(date.getYear(), date.getMonth(), date.getDay(),
            time.getHours(), time.getMinutes(), time.getSeconds());
        cal.set(Calendar.MILLISECOND, endOfSecond ? 999 : 0);
        return cal.getTimeInMillis();
    }

    private static String buildSummary(StatsResult result)
    {
        return NLS.bind(Messages.McpHistoryView_Summary, new Object[] {
            Long.valueOf(result.getTotalCalls()), result.getApproxTotalTokensDisplay(),
            Long.valueOf(result.getTotalOutputChars()), Long.valueOf(result.getTotalErrors())});
    }

    private static String buildTop3(StatsResult result)
    {
        List<ToolStats> top = result.getTop3();
        if (top.isEmpty())
        {
            return Messages.McpHistoryView_Top3None;
        }
        StringBuilder sb = new StringBuilder(Messages.McpHistoryView_Top3Prefix);
        for (int i = 0; i < top.size(); i++)
        {
            ToolStats ts = top.get(i);
            if (i > 0)
            {
                sb.append("    "); //$NON-NLS-1$
            }
            sb.append(i + 1).append(") ").append(ts.getToolName()) //$NON-NLS-1$
                .append(" (").append(ts.getApproxTokensDisplay()).append(')'); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * UI status classifier mirroring the package-private
     * {@code StatsAggregator.isErrorResponse}: a JSON-RPC top-level {@code error}
     * object, or a tool result carrying {@code isError:true} /
     * {@code success:false} (matched structurally, never by substring). Any parse
     * failure or non-object payload is treated as a non-error.
     */
    private static boolean isErrorResponse(String responseJson)
    {
        if (responseJson == null)
        {
            return false;
        }
        String trimmed = responseJson.trim();
        if (trimmed.isEmpty())
        {
            return false;
        }
        try
        {
            JsonElement element = JsonParser.parseString(trimmed);
            if (!element.isJsonObject())
            {
                return false;
            }
            JsonObject obj = element.getAsJsonObject();
            if (obj.has(KEY_ERROR) && obj.get(KEY_ERROR).isJsonObject())
            {
                return true;
            }
            JsonElement resultEl = obj.get(KEY_RESULT);
            if (resultEl != null && resultEl.isJsonObject())
            {
                JsonObject result = resultEl.getAsJsonObject();
                if (isBool(result, KEY_IS_ERROR, true))
                {
                    return true;
                }
                JsonElement sc = result.get(KEY_STRUCTURED_CONTENT);
                if (sc != null && sc.isJsonObject() && isBool(sc.getAsJsonObject(), KEY_SUCCESS, false))
                {
                    return true;
                }
                if (isBool(result, KEY_SUCCESS, false))
                {
                    return true;
                }
            }
            return isBool(obj, KEY_SUCCESS, false);
        }
        catch (RuntimeException e) // NOSONAR a non-JSON payload is classified as non-error, never thrown
        {
            return false;
        }
    }

    private static boolean isBool(JsonObject obj, String key, boolean expected)
    {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()
            && el.getAsBoolean() == expected;
    }

    private static ImageDescriptor pluginImage(String path)
    {
        return AbstractUIPlugin.imageDescriptorFromPlugin(Activator.PLUGIN_ID, path);
    }

    private static ImageDescriptor sharedImage(String symbolicName)
    {
        return PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(symbolicName);
    }

    // ------------------------------------------------------------- Lifecycle

    @Override
    public void setFocus()
    {
        if (historyViewer != null && !historyViewer.getControl().isDisposed())
        {
            historyViewer.getControl().setFocus();
        }
    }

    @Override
    public void dispose()
    {
        history.removeListener(this);
        super.dispose();
    }
}
