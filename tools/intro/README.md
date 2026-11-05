# Vinheta BruDre (7 s)

Gerada por código, sem material de terceiros:

- `render.py` — vídeo 1920×1080, 30 fps (numpy + OpenCV + Pillow), enviado ao ffmpeg (H.264 Main 4.0, yuv420p).
- `audio.py` — assinatura sonora original sintetizada (drone, feixes, faíscas, impacto, pad em Ré maior, sinos FM).
- Fonte: Poppins (SIL Open Font License), baixada de github.com/google/fonts.

```
python3 render.py video video.mp4
python3 audio.py
ffmpeg -i video.mp4 -i intro.wav -map 0:v -map 1:a -c:v copy -c:a aac -b:a 160k -shortest -movflags +faststart brudre_intro.mp4
```

O resultado fica em `app/src/main/res/raw/brudre_intro.mp4`.
