package cn.yooss.heic.backend;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * What the user can do about an unavailable {@link HeifBackend}: a title, an explanation and a list of
 * {@linkplain Action actions}, shown by the editor banner and the notifications of {@code cn.yooss.heic.ui}. Created by
 * {@link HeifRemedies#forStatus}.
 * <p>
 * Texts are resource bundle keys ({@code messages/HeicBundle.properties} and its {@code zh_CN} translation), so this
 * package needs no IDE classes; URLs and commands are data (not translated). Every URL is {@code https:} or
 * {@code ms-windows-store:} ({@link HeifRemedies#isAllowedUrl}), every command a single non-empty line.
 * <p>
 * Immutable. Hand-written value classes, not records (see {@link HeifBackendStatus}).
 */
public final class HeifRemedy {
  /** What an {@link Action} does. */
  public enum ActionType {
    /** Opens {@link Action#target()}: a store page ({@code ms-windows-store:}) or a web page ({@code https:}). */
    OPEN_URL,
    /** Copies {@link Action#target()}, a shell command that installs the missing component, to the clipboard. */
    COPY_COMMAND,
    /** Opens the IDE settings page whose configurable id is {@link Action#target()}. */
    OPEN_SETTINGS,
    /** Forgets the cached status and probes the system decoder again; HEIC images load without a restart once it is there. */
    CHECK_AGAIN,
    /** Opens {@link Action#target()} ({@code https:}): documentation of the requirements. */
    LEARN_MORE;

    /**
     * Whether performing the action may make the user install something outside the IDE, after which the plugin
     * checks again when the IDE window is activated.
     */
    public boolean startsInstallation() {
      return this == OPEN_URL || this == COPY_COMMAND || this == OPEN_SETTINGS;
    }
  }

  /** One action: its type, the bundle key of its label and its target (URL, command, configurable id or none). */
  public static final class Action {
    private final ActionType type;
    private final String textKey;
    private final @Nullable String target;

    private Action(@NotNull ActionType type, @NotNull String textKey, @Nullable String target) {
      this.type = Objects.requireNonNull(type, "type");
      this.textKey = Objects.requireNonNull(textKey, "textKey");
      this.target = target;
      if (type == ActionType.CHECK_AGAIN) {
        if (target != null) throw new IllegalArgumentException("CHECK_AGAIN has no target: " + target);
      }
      else if (target == null || target.trim().isEmpty()) {
        throw new IllegalArgumentException(type + " needs a target");
      }
      if ((type == ActionType.OPEN_URL || type == ActionType.LEARN_MORE) && !HeifRemedies.isAllowedUrl(target)) {
        throw new IllegalArgumentException("Not an https: or ms-windows-store: URL: " + target);
      }
      if (type == ActionType.COPY_COMMAND && !HeifRemedies.isAllowedCommand(target)) {
        throw new IllegalArgumentException("Not a single-line command: " + target);
      }
    }

    /** @param textKey bundle key of the label, e.g. {@code remedy.action.open.store} */
    public static @NotNull Action openUrl(@NotNull String textKey, @NotNull String url) {
      return new Action(ActionType.OPEN_URL, textKey, url);
    }

    public static @NotNull Action copyCommand(@NotNull String command) {
      return new Action(ActionType.COPY_COMMAND, "remedy.action.copy.command", command);
    }

    /** @param configurableId id of a {@code SearchableConfigurable}, e.g. {@code advanced.settings} */
    public static @NotNull Action openSettings(@NotNull String configurableId) {
      return new Action(ActionType.OPEN_SETTINGS, "remedy.action.open.settings", configurableId);
    }

    public static @NotNull Action checkAgain() {
      return new Action(ActionType.CHECK_AGAIN, "remedy.action.check.again", null);
    }

    public static @NotNull Action learnMore(@NotNull String url) {
      return new Action(ActionType.LEARN_MORE, "remedy.action.learn.more", url);
    }

    public @NotNull ActionType type() {
      return type;
    }

    /** Bundle key of the label. */
    public @NotNull String textKey() {
      return textKey;
    }

    /** URL ({@code OPEN_URL}, {@code LEARN_MORE}), command, configurable id, or {@code null} ({@code CHECK_AGAIN}). */
    public @Nullable String target() {
      return target;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Action)) return false;
      Action other = (Action) o;
      return type == other.type && textKey.equals(other.textKey) && Objects.equals(target, other.target);
    }

    @Override
    public int hashCode() {
      return Objects.hash(type, textKey, target);
    }

    @Override
    public String toString() {
      return type + "(" + textKey + (target != null ? ", " + target : "") + ")";
    }
  }

  private final HeifBackendStatus.Reason reason;
  private final @Nullable String command;
  private final boolean hostCommand;
  private final List<Action> actions;

  HeifRemedy(@NotNull HeifBackendStatus.Reason reason, @Nullable String command, @NotNull List<Action> actions) {
    this(reason, command, false, actions);
  }

  /** @param hostCommand whether {@code command} must run on the host, outside the IDE's Flatpak sandbox */
  HeifRemedy(@NotNull HeifBackendStatus.Reason reason, @Nullable String command, boolean hostCommand,
             @NotNull List<Action> actions) {
    this.reason = Objects.requireNonNull(reason, "reason");
    if (command != null && !HeifRemedies.isAllowedCommand(command)) throw new IllegalArgumentException("Bad command: " + command);
    if (hostCommand && command == null) throw new IllegalArgumentException("A host command needs a command");
    this.command = command;
    this.hostCommand = hostCommand;
    this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
  }

  public @NotNull HeifBackendStatus.Reason reason() {
    return reason;
  }

  /** Bundle key of the short title (notification title), {@code remedy.title.<REASON>}. */
  public @NotNull String titleKey() {
    return titleKey(reason);
  }

  /**
   * Bundle key of the explanation (banner tooltip, notification content): {@link HeifBackendStatus.Reason#bundleKey()},
   * with {@value #HOST_SUFFIX} for a {@linkplain #isHostCommand() host command} (the Flatpak runtime's extension instead of
   * the distribution's packages).
   */
  public @NotNull String explanationKey() {
    return hostCommand ? reason.bundleKey() + HOST_SUFFIX : reason.bundleKey();
  }

  /** Suffix of the bundle keys of the texts for a {@linkplain #isHostCommand() host command}. */
  public static final String HOST_SUFFIX = ".flatpak";

  /**
   * Whether {@link #command()} must be run in a terminal on the host: the IDE runs as a Flatpak, whose own terminal is
   * inside the sandbox, and the command installs an extension of its Flatpak runtime.
   */
  public boolean isHostCommand() {
    return hostCommand;
  }

  /**
   * The command that installs the missing component, shown with the explanation (and in the banner when copying it is
   * the first action); {@code null} if there is none.
   */
  public @Nullable String command() {
    return command;
  }

  /** The actions in the order they are offered (the most useful first). */
  public @NotNull List<Action> actions() {
    return actions;
  }

  /** The first action of {@code type}, or {@code null}. */
  public @Nullable Action action(@NotNull ActionType type) {
    for (Action action : actions) {
      if (action.type() == type) return action;
    }
    return null;
  }

  /** Bundle key of the title of {@code reason}. */
  public static @NotNull String titleKey(@NotNull HeifBackendStatus.Reason reason) {
    return "remedy.title." + reason.name();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof HeifRemedy)) return false;
    HeifRemedy other = (HeifRemedy) o;
    return reason == other.reason && Objects.equals(command, other.command) && hostCommand == other.hostCommand
           && actions.equals(other.actions);
  }

  @Override
  public int hashCode() {
    return Objects.hash(reason, command, hostCommand, actions);
  }

  @Override
  public String toString() {
    return "HeifRemedy(" + reason + (command != null ? (hostCommand ? ", host command " : ", command ") + command : "") + ", "
           + actions + ")";
  }
}
