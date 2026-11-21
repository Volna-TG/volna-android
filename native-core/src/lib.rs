mod crypto;

use jni::objects::{JByteArray, JClass, JObject};
use jni::sys::{jbyteArray, jintArray, jlong, jobjectArray};
use jni::JNIEnv;
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
use std::sync::atomic::{AtomicI64, Ordering};

static STREAMS: OnceLock<Mutex<HashMap<i64, Arc<Mutex<crypto::Stream>>>>> = OnceLock::new();
static NEXT: AtomicI64 = AtomicI64::new(1);
fn streams() -> &'static Mutex<HashMap<i64, Arc<Mutex<crypto::Stream>>>> { STREAMS.get_or_init(|| Mutex::new(HashMap::new())) }

#[no_mangle]
pub extern "system" fn Java_app_volna_NativeCrypto_create(mut env: JNIEnv, _: JClass, key: JByteArray, iv: JByteArray) -> jlong {
    let Ok(mut key) = env.convert_byte_array(&key) else { return 0; };
    let Ok(mut iv) = env.convert_byte_array(&iv) else { return 0; };
    let result = crypto::stream(&key, &iv);
    use zeroize::Zeroize;
    key.zeroize(); iv.zeroize();
    match result {
        Ok(cipher) => match streams().lock() {
            Ok(mut map) => { let id = NEXT.fetch_add(1, Ordering::Relaxed); map.insert(id, Arc::new(Mutex::new(cipher))); id }
            Err(_) => { let _ = env.throw_new("java/lang/IllegalStateException", "Cipher store unavailable"); 0 }
        },
        Err(message) => { let _ = env.throw_new("java/lang/IllegalArgumentException", message); 0 }
    }
}

#[no_mangle]
pub extern "system" fn Java_app_volna_NativeCrypto_apply(mut env: JNIEnv, _: JClass, handle: jlong, input: JByteArray) -> jbyteArray {
    let Ok(mut bytes) = env.convert_byte_array(&input) else { return std::ptr::null_mut(); };
    let cipher = streams().lock().ok().and_then(|map| map.get(&handle).cloned());
    let result = match cipher {
        Some(cipher) => match cipher.lock() { Ok(mut cipher) => crypto::apply(&mut cipher, &mut bytes), Err(_) => Err("Cipher unavailable") },
        None => Err("Cipher is closed"),
    };
    if let Err(message) = result { let _ = env.throw_new("java/lang/IllegalStateException", message); return std::ptr::null_mut(); }
    let output = env.byte_array_from_slice(&bytes).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut());
    use zeroize::Zeroize; bytes.zeroize();
    output
}

#[no_mangle]
pub extern "system" fn Java_app_volna_NativeCrypto_release(_: JNIEnv, _: JClass, handle: jlong) {
    if let Ok(mut map) = streams().lock() { map.remove(&handle); }
}

#[no_mangle]
pub extern "system" fn Java_app_volna_NativeCrypto_parseHandshake(env: JNIEnv, _: JClass, raw: JByteArray, secret: JByteArray) -> jintArray {
    let Ok(raw) = env.convert_byte_array(&raw) else { return std::ptr::null_mut(); };
    let Ok(mut secret) = env.convert_byte_array(&secret) else { return std::ptr::null_mut(); };
    let parsed = crypto::handshake(&raw, &secret);
    use zeroize::Zeroize; secret.zeroize();
    let Some(values) = parsed else { return std::ptr::null_mut(); };
    let Ok(array) = env.new_int_array(3) else { return std::ptr::null_mut(); };
    if env.set_int_array_region(&array, 0, &values).is_err() { return std::ptr::null_mut(); }
    array.into_raw()
}

fn apply_pair<F>(decrypt: i64, encrypt: i64, bytes: &mut [u8], middle: F) -> Result<(), &'static str>
where F: FnOnce(&[u8]) -> Result<(), &'static str> {
    if decrypt == encrypt { return Err("Invalid cipher pair"); }
    let (dec, enc) = {
        let map = streams().lock().map_err(|_| "Cipher store unavailable")?;
        (map.get(&decrypt).cloned().ok_or("Cipher is closed")?, map.get(&encrypt).cloned().ok_or("Cipher is closed")?)
    };
    if decrypt < encrypt {
        let mut dec = dec.lock().map_err(|_| "Cipher unavailable")?;
        let mut enc = enc.lock().map_err(|_| "Cipher unavailable")?;
        crypto::apply(&mut dec, bytes)?; middle(bytes)?; crypto::apply(&mut enc, bytes)
    } else {
        let mut enc = enc.lock().map_err(|_| "Cipher unavailable")?;
        let mut dec = dec.lock().map_err(|_| "Cipher unavailable")?;
        crypto::apply(&mut dec, bytes)?; middle(bytes)?; crypto::apply(&mut enc, bytes)
    }
}

#[no_mangle]
pub extern "system" fn Java_app_volna_NativeCrypto_transcodeNative(mut env: JNIEnv, _: JClass, decrypt: jlong, encrypt: jlong, input: JByteArray) -> jbyteArray {
    let Ok(mut bytes) = env.convert_byte_array(&input) else { return std::ptr::null_mut(); };
    let result = apply_pair(decrypt, encrypt, &mut bytes, |_| Ok(()));
    if let Err(message) = result { let _ = env.throw_new("java/lang/IllegalStateException", message); return std::ptr::null_mut(); }
    let output = env.byte_array_from_slice(&bytes).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut());
    use zeroize::Zeroize; bytes.zeroize(); output
}

#[no_mangle]
pub extern "system" fn Java_app_volna_NativeCrypto_transcodePacketNative(mut env: JNIEnv, _: JClass, decrypt: jlong, encrypt: jlong, input: JByteArray) -> jobjectArray {
    let Ok(mut bytes) = env.convert_byte_array(&input) else { return std::ptr::null_mut(); };
    let mut plain = None;
    let result = apply_pair(decrypt, encrypt, &mut bytes, |value| {
        plain = Some(env.byte_array_from_slice(value).map_err(|_| "Output allocation failed")?); Ok(())
    });
    if let Err(message) = result { let _ = env.throw_new("java/lang/IllegalStateException", message); return std::ptr::null_mut(); }
    let Ok(encrypted) = env.byte_array_from_slice(&bytes) else { return std::ptr::null_mut(); };
    use zeroize::Zeroize; bytes.zeroize();
    let Ok(output) = env.new_object_array(2, "[B", JObject::null()) else { return std::ptr::null_mut(); };
    let Some(plain) = plain else { return std::ptr::null_mut(); };
    if env.set_object_array_element(&output, 0, plain).is_err() || env.set_object_array_element(&output, 1, encrypted).is_err() { return std::ptr::null_mut(); }
    output.into_raw()
}
