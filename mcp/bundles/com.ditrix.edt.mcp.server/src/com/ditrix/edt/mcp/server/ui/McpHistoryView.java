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
import java.util.List;
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
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault()); //$NON-NLS-1$

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
        SashForm sash = new SashForm(parent, SWT.VERTICAL);

        createHistoryTable(sash);
        createDetailPane(sash);

        sash.setWeights(60, 40);
        return sash;
    }

    private void createHistoryTable(Composite parent)
    {
        Table table = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.SINGLE);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        historyViewer = new TableViewer(table);
        historyViewer.setContentProvider(ArrayContentProvider.getInstance());

        addHistoryColumn(Messages.McpHistoryView_ColumnTime, 110, new ColumnLabelProvider()
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
                    return isErrorResponse(r.getResponseJson())
                        ? Messages.McpHistoryView_StatusError : Messages.McpHistoryView_StatusOk;
                }
                return ""; //$NON-NLS-1$
            }

            @Override
            public Color getForeground(Object element)
            {
                if (element instanceof McpCallRecord r && isErrorResponse(r.getResponseJson()))
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

    private void refreshHistoryTable()
    {
        if (historyViewer == null || historyViewer.getControl().isDisposed())
        {
            return;
        }
        tableRows.clear();
        for (McpCallRecord record : history.snapshot())
        {
            if (toolsCallOnly && !McpConstants.METHOD_TOOLS_CALL.equals(record.getMethod()))
            {
                continue;
            }
            tableRows.add(record);
        }
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
        StatsResult result = StatsAggregator.aggregate(history.snapshot());
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
            detailText.setText(""); //$NON-NLS-1$
            copyJsonButton.setEnabled(false);
            return;
        }
        setDetailText(buildDetail(record));
        copyJsonButton.setEnabled(true);
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

    private String buildDetail(McpCallRecord record)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(Messages.McpHistoryView_RequestSection).append('\n');
        sb.append(prettyOrNone(record.getRequestJson()));
        sb.append("\n\n").append(Messages.McpHistoryView_ResponseSection).append('\n'); //$NON-NLS-1$
        sb.append(prettyOrNone(record.getResponseJson()));
        return sb.toString();
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
        // Gson emits '\n'; normalise to the platform delimiter so the Text control on
        // every OS renders line breaks instead of control glyphs.
        detailText.setText(text.replace("\n", Text.DELIMITER)); //$NON-NLS-1$
    }

    private void copyDetailToClipboard()
    {
        String text = detailText.getText();
        if (text.isEmpty())
        {
            return;
        }
        Clipboard clipboard = new Clipboard(detailText.getDisplay());
        try
        {
            clipboard.setContents(new Object[] {text}, new Transfer[] {TextTransfer.getInstance()});
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
