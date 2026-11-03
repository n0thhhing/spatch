// p.S_KotlinMutableStateFlow: Kotlin MutableStateFlow. Not called by helper code.
package p;
// Compile-only Spotify 9.1.98.521 contract.
public final class S_KotlinMutableStateFlow implements S_KotlinFlow { private Object value; public S_KotlinMutableStateFlow(Object v){value=v;} public void l(Object v){value=v;} public Object getValue(){return value;} }
