import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from videostore.main import VideoStoreApp

if __name__ == "__main__":
    VideoStoreApp().run()
