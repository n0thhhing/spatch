package android.app;

public class AlertDialog {
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

        public AlertDialog show() {
            shown = true;
            return new AlertDialog();
        }
    }
}
