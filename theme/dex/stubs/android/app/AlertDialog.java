package android.app;

public class AlertDialog implements android.content.DialogInterface {
    public android.view.Window getWindow() { return null; }
    public android.widget.Button getButton(int which) { return null; }
    public void setCancelable(boolean c) {}
    public void show() {}
    public void dismiss() {}
    public static class Builder {
        public static CharSequence lastTitle;
        public static CharSequence lastMessage;
        public static android.content.DialogInterface.OnClickListener lastPositive;
        public static android.content.DialogInterface.OnClickListener lastNegative;
        public static boolean shown;

        public Builder(android.content.Context c) {}
        public Builder(android.content.Context c, int theme) {}

        public Builder setTitle(CharSequence s) {
            lastTitle = s;
            return this;
        }

        public Builder setMessage(CharSequence s) {
            lastMessage = s;
            return this;
        }

        public Builder setPositiveButton(CharSequence s, android.content.DialogInterface.OnClickListener l) {
            lastPositive = l;
            return this;
        }

        public Builder setNegativeButton(CharSequence s, android.content.DialogInterface.OnClickListener l) {
            lastNegative = l;
            return this;
        }

        public Builder setOnDismissListener(android.content.DialogInterface.OnDismissListener l) { return this; }
        public Builder setView(android.view.View v) { return this; }
        public Builder setNeutralButton(CharSequence s, android.content.DialogInterface.OnClickListener l) { return this; }
        public AlertDialog create() { return new AlertDialog(); }
        public AlertDialog show() {
            shown = true;
            return new AlertDialog();
        }
    }
}
