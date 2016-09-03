package compose.player

import compose.core._
import org.scalajs.dom.{XMLHttpRequest, Event}
import org.scalajs.dom.ext.Ajax
import org.scalajs.dom.raw.{AudioContext, AudioBuffer, AudioBufferSourceNode}
import scalajs.js.typedarray.ArrayBuffer
import scala.concurrent.{ExecutionContext => EC, _}
import scalajs.js
import scalajs.js.annotation.JSExport

object WebAudioPlayer {
  case class State(
    context: AudioContext,
    buffer: AudioBuffer,
    playing: Set[Command.NoteOn] = Set.empty,
    sources: Map[Int, AudioBufferSourceNode] = Map.empty
  )
}

@JSExport
class WebAudioPlayer(
  sampleUrl: String,
  context: AudioContext = new AudioContext(),
  callback: Option[(WebAudioPlayer.State, Command) => Unit] = None
) extends Player[WebAudioPlayer.State] {
  import WebAudioPlayer._
  import Command._

  def initialise: Future[State] = {
    var request = new XMLHttpRequest()
    request.open("GET", sampleUrl, true)
    request.responseType = "arraybuffer"

    var promise = Promise[State]
    request.onload = (evt: Event) =>
      context.decodeAudioData(
        request.response.asInstanceOf[ArrayBuffer],
        (buffer: AudioBuffer) => promise.success(State(context, buffer))
      )

    request.send()
    promise.future
  }

  def playCommand(state: State, cmd: Command)(implicit ec: EC, tempo: Tempo): Future[State] = {
    (cmd match {
      case cmd: NoteOn =>
        Future {
          var source = state.context.createBufferSource()
          source.buffer = state.buffer
          source.connect(state.context.destination)
          source.playbackRate.setValueAtTime(cmd.pitch.frequency / 220.0, 0)
          source.start(0)
          state.copy(
            playing = state.playing + cmd,
            sources = state.sources + (cmd.id -> source)
          )
        }

      case cmd: NoteOff =>
        state.sources.get(cmd.id).foreach(_.stop(0))
        Future.successful(state.copy(
          playing = state.playing filterNot (_.id == cmd.id),
          sources = state.sources - cmd.id
        ))

      case cmd: Wait =>
        val promise = Promise[State]
        js.timers.setTimeout(tempo.milliseconds(cmd.duration)) {
          promise.success(state)
        }
        promise.future
    }) map { state =>
      callback.foreach(_(state, cmd))
      state
    }
  }
}
