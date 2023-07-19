package com.lowdragmc.photon.client.emitter.data.shape;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.utils.Vector3;
import com.lowdragmc.photon.client.particle.LParticle;
import expr.Expr;
import expr.Parser;
import expr.SyntaxException;
import expr.Variable;
import lombok.Getter;
import lombok.Setter;

/**
 * @author KilaBash
 * @date 2023/5/29
 * @implNote Circle
 */
@LDLRegister(name = "function", group = "shape")
public class Function implements IShape {
    @Getter
    @Setter
    @Configurable(tips = {
            "photon.gui.editor.shape.function.tooltips.0",
            "photon.gui.editor.shape.function.tooltips.1",
            "photon.gui.editor.shape.function.tooltips.2",
            "photon.gui.editor.shape.function.tooltips.3"
    })
    private String x = "0";
    @Getter
    @Setter
    @Configurable(tips = {
            "photon.gui.editor.shape.function.tooltips.0",
            "photon.gui.editor.shape.function.tooltips.1",
            "photon.gui.editor.shape.function.tooltips.2",
            "photon.gui.editor.shape.function.tooltips.3"
    })
    private String y = "0";
    @Getter
    @Setter
    @Configurable(tips = {
            "photon.gui.editor.shape.function.tooltips.0",
            "photon.gui.editor.shape.function.tooltips.1",
            "photon.gui.editor.shape.function.tooltips.2",
            "photon.gui.editor.shape.function.tooltips.3"
    })
    private String z = "0";

    @Getter
    @Setter
    @Configurable(tips = {
            "photon.gui.editor.shape.function.tooltips.0",
            "photon.gui.editor.shape.function.tooltips.1",
            "photon.gui.editor.shape.function.tooltips.2",
            "photon.gui.editor.shape.function.tooltips.3"
    })
    private String speedX = "0";
    @Getter
    @Setter
    @Configurable(tips = {
            "photon.gui.editor.shape.function.tooltips.0",
            "photon.gui.editor.shape.function.tooltips.1",
            "photon.gui.editor.shape.function.tooltips.2",
            "photon.gui.editor.shape.function.tooltips.3"
    })
    private String speedY = "0";
    @Getter
    @Setter
    @Configurable(tips = {
            "photon.gui.editor.shape.function.tooltips.0",
            "photon.gui.editor.shape.function.tooltips.1",
            "photon.gui.editor.shape.function.tooltips.2",
            "photon.gui.editor.shape.function.tooltips.3"
    })
    private String speedZ = "0";

    private Expr xCache, yCache, zCache, sXCache, sYCache, sZCache;
    private static final Variable T = Variable.make("t");
    private static final Variable PI = Variable.make("PI");
    private static final Variable randomA = Variable.make("randomA");
    private static final Variable randomB = Variable.make("randomB");
    private static final Variable randomC = Variable.make("randomC");
    private static final Variable randomD = Variable.make("randomD");
    private static final Variable randomE = Variable.make("randomE");
    static {
        PI.setValue(Math.PI);
    }

    private void prepareExpr(LParticle emitter) {
        T.setValue(emitter.getT());

        randomA.setValue(emitter.getRandomSource().nextFloat());
        randomB.setValue(emitter.getRandomSource().nextFloat());
        randomC.setValue(emitter.getRandomSource().nextFloat());
        randomD.setValue(emitter.getRandomSource().nextFloat());
        randomE.setValue(emitter.getRandomSource().nextFloat());

        if (xCache == null || !x.equals(xCache.getInput())) {
            try {
                xCache = Parser.parse(x);
            } catch (SyntaxException ignored) {
            }
        }
        if (yCache == null || !y.equals(yCache.getInput())) {
            try {
                yCache = Parser.parse(y);
            } catch (SyntaxException ignored) {
            }
        }
        if (zCache == null || !z.equals(zCache.getInput())) {
            try {
                zCache = Parser.parse(z);
            } catch (SyntaxException ignored) {
            }
        }

        if (sXCache == null || !speedX.equals(sXCache.getInput())) {
            try {
                sXCache = Parser.parse(speedX);
            } catch (SyntaxException ignored) {
            }
        }
        if (sYCache == null || !speedY.equals(sYCache.getInput())) {
            try {
                sYCache = Parser.parse(speedY);
            } catch (SyntaxException ignored) {
            }
        }
        if (sZCache == null || !speedZ.equals(sZCache.getInput())) {
            try {
                sZCache = Parser.parse(speedZ);
            } catch (SyntaxException ignored) {
            }
        }
    }

    @Override
    public void nextPosVel(LParticle particle, LParticle emitter, Vector3 position, Vector3 rotation, Vector3 scale) {
        prepareExpr(emitter);
        var pos = new Vector3(xCache != null ? xCache.value() : 0, yCache != null ? yCache.value() : 0, zCache != null ? zCache.value() : 0);
        var speed = new Vector3(sXCache != null ? sXCache.value() : 0, sYCache != null ? sYCache.value() : 0, sZCache != null ? sZCache.value() : 0);
        particle.setPos(pos.copy().rotateYXY(rotation).add(position).add(particle.getPos()), true);
        particle.setSpeed(speed.normalize().multiply(0.05).rotateYXY(rotation));
    }
}
