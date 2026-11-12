#!/usr/bin/env python3
"""Check the pinned Python bundle's output contract using localhost and host FFmpeg.
This does not execute the Android binaries or establish device acceptance.
Run after Gradle has resolved the pinned engine AAR. No external requests are made.
"""
import http.server, threading, tempfile, wave, subprocess, sys, os, zipfile, shutil
from pathlib import Path
with tempfile.TemporaryDirectory() as directory:
    root=Path(directory)
    if not shutil.which('ffmpeg'):
        sys.exit('Host FFmpeg is required for this smoke test.')
    cache=Path(os.environ.get('GRADLE_USER_HOME', Path.home()/'.gradle'))/'caches/modules-2/files-2.1/io.github.junkfood02.youtubedl-android/library/0.18.1'
    aar=next(cache.rglob('*.aar'), None)
    if aar is None:
        sys.exit('Resolve the pinned engine with Gradle first.')
    bundle=root/'ytdlp'
    with zipfile.ZipFile(aar) as archive:
        bundle.write_bytes(archive.read('res/raw/ytdlp'))
    with wave.open(str(root/'fixture.wav'),'wb') as wav:
        wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(44100); wav.writeframes(b'\x00\x00'*88200)
    class Handler(http.server.SimpleHTTPRequestHandler):
        def __init__(self,*a,**kw): super().__init__(*a,directory=directory,**kw)
        def log_message(self,*a): pass
    shutil.copyfile(root/'fixture.wav', root/'second.wav')
    (root/'playlist.html').write_text('<html><title>Local playlist</title><audio src="fixture.wav" controls></audio><audio src="second.wav" controls></audio></html>')
    server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    url=f'http://127.0.0.1:{server.server_port}/fixture.wav'
    args=[sys.executable,str(bundle),url,'--no-playlist','--restrict-filenames','--no-mtime','--continue','--newline','--no-colors','--no-quiet','--progress','--no-simulate','--print','after_move:VSFILE|%(filepath)s','--progress-template','download:VS|%(progress._percent_str)s|%(progress._speed_str)s|%(progress._eta_str)s','-f','ba/b','-o',str(root/'media.%(ext)s'),'-x','--audio-format','mp3']
    result=subprocess.run(args,capture_output=True,text=True,timeout=60)
    assert result.returncode==0, result.stderr
    assert any(line.startswith('VS|') for line in result.stdout.splitlines()), result.stdout
    assert '[ExtractAudio]' in result.stdout, result.stdout
    path=next(line.split('|',1)[1] for line in result.stdout.splitlines() if line.startswith('VSFILE|'))
    assert Path(path).is_file() and Path(path).suffix=='.mp3'
    import json
    playlist_url=f'http://127.0.0.1:{server.server_port}/playlist.html'
    listing=subprocess.run([sys.executable,str(bundle),playlist_url,'--yes-playlist','--flat-playlist','--dump-single-json'],capture_output=True,text=True,timeout=60)
    assert listing.returncode==0, listing.stderr
    info=json.loads(listing.stdout)
    assert len(info['entries'])==2, info
    for index in (1,2):
        selected=subprocess.run([sys.executable,str(bundle),playlist_url,'--yes-playlist','--playlist-items',str(index),'--dump-single-json'],capture_output=True,text=True,timeout=60)
        assert selected.returncode==0, selected.stderr
        entry=json.loads(selected.stdout)
        assert len(entry['entries'])==1, entry
        output_dir=root/f'item-{index}'
        output_dir.mkdir()
        selected_args=[sys.executable,str(bundle),playlist_url,'--yes-playlist','--playlist-items',str(index),'--no-quiet','--no-simulate','--print','after_move:VSFILE|%(filepath)s','-f','ba/b','-o',str(output_dir/'media.%(ext)s')]
        downloaded=subprocess.run(selected_args,capture_output=True,text=True,timeout=60)
        assert downloaded.returncode==0, downloaded.stderr
        paths=[line.split('|',1)[1] for line in downloaded.stdout.splitlines() if line.startswith('VSFILE|')]
        assert len(paths)==1 and Path(paths[0]).read_bytes()==(root/'fixture.wav').read_bytes(), downloaded.stdout
    server.shutdown()
    print('Bundled yt-dlp host smoke passed: MP3 conversion/progress and two-item playlist listing, selection and serial downloads (host FFmpeg, not Android runtime).')
