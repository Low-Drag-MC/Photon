package com.lowdragmc.photon.uitest;

import com.lowdragmc.lowdraglib2.configurator.ui.BooleanConfigurator;
import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Menu;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.uitest.ElementBounds;
import com.lowdragmc.lowdraglib2.uitest.ElementRef;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.input.Keys;
import com.lowdragmc.photon.gui.editor.FXEditor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Finding and clicking things in the real editor, the way the Kila editor scenarios do it. */
final class KilaUiTestKit {
    private KilaUiTestKit() {
    }

    @Nullable
    static UIElement confirmButton(TestContext ctx) {
        return ctx.requireUI().ui.rootElement.selfAndAllChildren()
                .filter(e -> e.hasClass("__confirm-button__") && shown(e))
                .findFirst().orElse(null);
    }

    /** An entry of an open dropdown, which lives on the root rather than under its selector. */
    @Nullable
    static UIElement selectorEntry(TestContext ctx, String text) {
        return ctx.query().type(TextElement.class).list().stream()
                .filter(ref -> text.equals(ref.text()))
                .map(ElementRef::element)
                .filter(element -> {
                    for (var e = element; e != null; e = e.getParent()) {
                        if (e.hasClass("__selector_dialog__")) return true;
                    }
                    return false;
                })
                .findFirst().orElse(null);
    }

    static FXEditor editor(TestContext ctx) {
        return ctx.query().type(FXEditor.class).one().as(FXEditor.class);
    }

    /** {@code visible()} looks at the element alone; a label inside a hidden group is not shown either. */
    static boolean shown(UIElement element) {
        for (var e = element; e != null; e = e.getParent()) {
            if (!e.isVisible() || !e.isDisplayed()) return false;
        }
        return true;
    }

    /** A shown label with exactly this text, outside any menu. */
    @Nullable
    static UIElement label(TestContext ctx, String text) {
        return ctx.query().type(TextElement.class).list().stream()
                .filter(ref -> text.equals(ref.text()))
                .map(ElementRef::element)
                .filter(element -> element.getFirstAncestorOfType(Menu.class) == null && shown(element))
                .findFirst().orElse(null);
    }

    @Nullable
    static UIElement labelStarting(TestContext ctx, String prefix) {
        return ctx.query().type(TextElement.class).list().stream()
                .filter(ref -> ref.text() != null && ref.text().startsWith(prefix))
                .map(ElementRef::element)
                .filter(KilaUiTestKit::shown)
                .findFirst().orElse(null);
    }

    /** Menu labels are internal elements, which {@code withText} skips. */
    @Nullable
    static UIElement menuEntry(TestContext ctx, String text) {
        return ctx.query().type(TextElement.class).visible().list().stream()
                .filter(ref -> text.equals(ref.text()))
                .map(ElementRef::element)
                .filter(element -> element.getFirstAncestorOfType(Menu.class) != null)
                .findFirst().orElse(null);
    }

    @Nullable
    static ConfiguratorGroup groupOf(@Nullable UIElement label) {
        return label == null ? null : label.getFirstAncestorOfType(ConfiguratorGroup.class);
    }

    @Nullable
    static Toggle headerToggle(@Nullable ConfiguratorGroup group) {
        if (group == null) return null;
        for (var child : group.lineContainer.getChildren()) {
            if (child instanceof Toggle toggle) return toggle;
        }
        return null;
    }

    /** Scroll the target into view, then press and release where it was resolved once: an LDLib2 menu acts on mouse down. */
    static void clickOn(ScenarioBuilder s, String label, Function<TestContext, UIElement> target) {
        s.step("scroll to " + label, ctx -> {
            var element = target.apply(ctx);
            ctx.require(label + " exists", element != null);
            scrollIntoView(element);
        });
        s.frames(2);
        s.step("hover " + label, ctx -> {
            var at = ctx.put("clickAt", centreOf(Objects.requireNonNull(target.apply(ctx), label)));
            ctx.input().moveTo(at[0], at[1]);
        });
        s.step("press " + label, ctx -> {
            var at = ctx.<float[]>get("clickAt");
            ctx.input().mouseDown(at[0], at[1], Keys.MOUSE_LEFT);
        });
        s.step("release " + label, ctx -> {
            var at = ctx.<float[]>get("clickAt");
            ctx.input().mouseUp(at[0], at[1], Keys.MOUSE_LEFT);
        });
    }

    /** {@code ScrollerView.scrollToChild}, for an element any depth inside the scroll view. */
    static void scrollIntoView(UIElement element) {
        var scroller = element.getFirstAncestorOfType(ScrollerView.class);
        if (scroller == null) return;
        float offset = element.getPositionY() - scroller.viewContainer.getPositionY();
        float size = element.getSizeHeight();
        float port = scroller.viewPort.getContentHeight();
        float range = scroller.getContainerHeight() - port;
        if (range <= 0) return;
        float scrolled = scroller.verticalScroller.getNormalizedValue() * range;
        if (Float.isNaN(scrolled)) scrolled = 0;
        float target = scrolled;
        if (offset < scrolled) {
            target = offset;
        } else if (offset + size > scrolled + port) {
            target = offset + size - port;
        }
        scroller.verticalScroller.setNormalizedValue(Math.clamp(target / range, 0f, 1f));
    }

    static float[] centreOf(UIElement element) {
        var bounds = ElementBounds.of(element);
        return new float[]{bounds.centerX(), bounds.centerY()};
    }

    /** A label inside an open dialog. */
    @Nullable
    static UIElement dialogLabel(TestContext ctx, String text) {
        return ctx.query().type(TextElement.class).list().stream()
                .filter(ref -> text.equals(ref.text()))
                .map(ElementRef::element)
                .filter(element -> element.getFirstAncestorOfType(Dialog.class) != null && shown(element))
                .findFirst().orElse(null);
    }

    /** The button on the line of a dialog row labelled {@code text}. */
    @Nullable
    static UIElement dialogRowButton(TestContext ctx, String text) {
        var label = dialogLabel(ctx, text);
        if (label == null || label.getParent() == null) return null;
        return label.getParent().selfAndAllChildren().filter(e -> e instanceof Button).findFirst().orElse(null);
    }

    /** Opens every group of the inspector, the rarely touched halves too, so all of its rows are laid out. */
    static void openEveryGroup(TestContext ctx) {
        for (var ref : ctx.query().type(ConfiguratorGroup.class).list()) {
            var group = ref.as(ConfiguratorGroup.class);
            if (group.isCanCollapse() && group.isCollapse()) group.setCollapse(false);
        }
    }

    /** The checkboxes a long label pushed past the edge of their row, where they cannot be seen or clicked. */
    static List<String> hiddenCheckboxes(TestContext ctx) {
        var hidden = new ArrayList<String>();
        for (var ref : ctx.query().type(BooleanConfigurator.class).list()) {
            var row = ref.as(BooleanConfigurator.class);
            if (!shown(row)) continue;
            var toggle = row.toggle;
            float right = toggle.getPositionX() + toggle.getSizeWidth();
            if (toggle.getSizeWidth() < 1 || right > row.getPositionX() + row.getSizeWidth() + 0.5f) {
                hidden.add(row.getLabel().getString());
            }
        }
        return hidden;
    }

    /** Scrolls so {@code element} sits at the top of its scroll view, for a capture of what follows it. */
    static void scrollToTop(UIElement element) {
        var scroller = element.getFirstAncestorOfType(ScrollerView.class);
        if (scroller == null) return;
        float offset = element.getPositionY() - scroller.viewContainer.getPositionY();
        float port = scroller.viewPort.getContentHeight();
        float range = scroller.getContainerHeight() - port;
        if (range <= 0) return;
        scroller.verticalScroller.setNormalizedValue(Math.clamp(offset / range, 0f, 1f));
    }
}
