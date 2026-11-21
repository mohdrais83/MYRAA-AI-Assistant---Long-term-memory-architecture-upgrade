export class MyraaWakeWordDetector {
  private recognition: any = null;
  private active = false;

  start(onWake: () => void, wakeWord = "myraa") {
    const SpeechRecognition =
      (window as any).SpeechRecognition ||
      (window as any).webkitSpeechRecognition;

    if (!SpeechRecognition) {
      console.warn("Speech recognition is not supported in this browser.");
      return;
    }

    this.recognition = new SpeechRecognition();
    this.recognition.continuous = true;
    this.recognition.interimResults = true;

    this.recognition.onresult = (event: any) => {
      const text = Array.from(event.results)
        .map((result: any) => result[0].transcript)
        .join(" ")
        .toLowerCase();

      if (text.includes(wakeWord.toLowerCase())) {
        onWake();
      }
    };

    this.recognition.onend = () => {
      if (this.active) {
        try { this.recognition.start(); } catch {}
      }
    };

    this.active = true;
    this.recognition.start();
  }

  stop() {
    this.active = false;
    this.recognition?.stop();
  }
}
