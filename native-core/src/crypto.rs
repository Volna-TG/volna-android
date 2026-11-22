use aes::Aes256;
use ctr::cipher::{KeyIvInit, StreamCipher};
use sha2::{Digest, Sha256};

pub type Stream = ctr::Ctr128BE<Aes256>;

pub fn stream(key: &[u8], iv: &[u8]) -> Result<Stream, &'static str> {
    Stream::new_from_slices(key, iv).map_err(|_| "Invalid cipher parameters")
}

pub fn apply(cipher: &mut Stream, bytes: &mut [u8]) -> Result<(), &'static str> {
    cipher.try_apply_keystream(bytes).map_err(|_| "Cipher counter exhausted")
}

pub fn handshake(raw: &[u8], secret: &[u8]) -> Option<[i32; 3]> {
    if raw.len() != 64 || secret.len() != 16 { return None; }
    let mut digest = Sha256::new();
    digest.update(&raw[8..40]);
    digest.update(secret);
    let mut key = digest.finalize();
    let mut cipher = stream(&key, &raw[40..56]).ok()?;
    use zeroize::Zeroize;
    key.as_mut_slice().zeroize();
    let mut plain = [0u8; 64];
    plain.copy_from_slice(raw);
    apply(&mut cipher, &mut plain).ok()?;
    let tag = u32::from_le_bytes(plain[56..60].try_into().ok()?);
    if !matches!(tag, 0xefefefef | 0xeeeeeeee | 0xdddddddd) { plain.zeroize(); return None; }
    let dc = i16::from_le_bytes(plain[60..62].try_into().ok()?) as i32;
    plain.zeroize();
    let abs = dc.abs();
    if abs == 0 || abs > 1000 { return None; }
    Some([abs, i32::from(dc < 0), tag as i32])
}
