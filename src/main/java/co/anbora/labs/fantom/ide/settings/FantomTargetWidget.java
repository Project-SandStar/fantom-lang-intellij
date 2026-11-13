package co.anbora.labs.fantom.ide.settings;

import co.anbora.labs.fantom.lang.library.deps.FantomDependencies;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.WindowManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The status bar entry "Fantom: SkySpark 4.0.7": the current build target. A click lists
 * the ticked installs; picking one calls {@link FantomDependencies#switchTarget}, which
 * re-maps every dependency (Haxall pairing, tag choice) and fires a roots change.
 * <p>
 * Written in Java on purpose: a Kotlin class implementing these platform interfaces gets
 * compiler-made overrides of their deprecated default methods (getPopupStep, getMaxValue,
 * getPresentation(PlatformType)), which the plugin verifier reports. The logic is in
 * {@link FantomTargetWidgets}.
 */
public final class FantomTargetWidget implements StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {
    public static final String ID = "co.anbora.labs.fantom.target";

    private final Project project;
    private StatusBar statusBar;

    public FantomTargetWidget(@NotNull Project project) {
        this.project = project;
        project.getMessageBus().connect(this).subscribe(FantomDependencies.Companion.getTOPIC(), new FantomDependencies.Listener() {
            @Override
            public void dependenciesChanged(@NotNull FantomDependencies.Snapshot snapshot) {
                update();
            }

            @Override
            public void targetChanged(@NotNull String path) {
                update();
            }
        });
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
        return FantomTargetWidgets.INSTANCE.text(project);
    }

    @Override
    public @Nullable String getTooltipText() {
        return "Fantom build target: the install the index, runs and compile on save use. Click to switch.";
    }

    @Override
    public @Nullable JBPopup getPopup() {
        return FantomTargetWidgets.INSTANCE.popup(project);
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
