package android.content;

public interface DialogInterface {
    int BUTTON_POSITIVE = -1;
    int BUTTON_NEGATIVE = -2;
    int BUTTON_NEUTRAL = -3;

    void dismiss();

    interface OnDismissListener {
        void onDismiss(DialogInterface d);
    }

    interface OnClickListener {
        void onClick(DialogInterface d, int which);
    }
}
