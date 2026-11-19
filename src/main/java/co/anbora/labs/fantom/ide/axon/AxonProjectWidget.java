package co.anbora.labs.fantom.ide.axon;

import co.anbora.labs.fantom.lang.axon.copy.AxonProjectCopy;
import co.anbora.labs.fantom.lang.axon.proj.AxonProjFolders;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The status bar entry "Axon: demo/site" (or "Axon: none"): the Axon project of the file in
 * the editor when it is in a {@code proj} folder, else the SkySpark project whose Axon
 * functions the IDE holds a read-only copy of. A click offers Pick, Refresh and
 * Drop. The popup itself reads memory only; the list of projects is fetched in a
 * background task after "Pick".
 * <p>
 * Written in Java for the same reason as FantomTargetWidget: a Kotlin class implementing
 * these platform interfaces gets compiler-made overrides of their deprecated default
 * methods, which the plugin verifier reports. The logic is in {@link AxonProjectWidgets}.
 */
public final class AxonProjectWidget implements StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {
    public static final String ID = "co.anbora.labs.fantom.axonProject";

    private final Project project;
    private StatusBar statusBar;

    public AxonProjectWidget(@NotNull Project project) {
        this.project = project;
        MessageBusConnection bus = project.getMessageBus().connect(this);
        bus.subscribe(AxonProjectCopy.Companion.getTOPIC(), this::update);
        // the entry shows the project of the file in the editor (task 4.38)
        bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override
            public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                update();
            }
        });
        ApplicationManager.getApplication().getMessageBus().connect(this)
            .subscribe(AxonProjFolders.Companion.getTOPIC(), this::update);
    }

    @Override
    public @NotNull String ID() {
        return ID;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
    }

    @Override
    public @NotNull WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public @Nullable String getSelectedValue() {
        return AxonProjectWidgets.INSTANCE.text(project);
    }

    @Override
    public @Nullable String getTooltipText() {
        return AxonProjectWidgets.INSTANCE.tooltip(project);
    }

    @Override
    public @Nullable JBPopup getPopup() {
        return AxonProjectWidgets.INSTANCE.popup(project);
    }

    private void update() {
        StatusBar bar = statusBar != null ? statusBar : WindowManager.getInstance().getStatusBar(project);
        if (bar != null) bar.updateWidget(ID);
    }

    @Override
    public void dispose() {
        statusBar = null;
    }
}
