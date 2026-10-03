package com.carmusic.app.engine

import kotlin.math.*

/** Bounded Hann-window FFT shared by live PCM preview and offline video rendering. */
internal class AudioSpectrum(private val size:Int=1024,private val bands:Int=48) {
    private val samples=FloatArray(size)
    private var cursor=0
    private var count=0
    fun add(sample:Float){samples[cursor]=sample;cursor=(cursor+1)%size;count=(count+1).coerceAtMost(size)}
    fun reset(){samples.fill(0f);cursor=0;count=0}
    fun levels(sampleRate:Int):FloatArray {
        val real=DoubleArray(size);val imaginary=DoubleArray(size)
        for(i in 0 until size) real[i]=samples[(cursor+i)%size]*(.5-.5*cos(2*PI*i/(size-1)))
        var j=0
        for(i in 1 until size){var bit=size shr 1;while(j and bit!=0){j=j xor bit;bit=bit shr 1};j=j xor bit;if(i<j){val swap=real[i];real[i]=real[j];real[j]=swap}}
        var length=2
        while(length<=size){val angle=-2*PI/length;val cr=cos(angle);val ci=sin(angle)
            for(start in 0 until size step length){var wr=1.0;var wi=0.0;for(k in 0 until length/2){val a=start+k;val b=a+length/2;val tr=wr*real[b]-wi*imaginary[b];val ti=wr*imaginary[b]+wi*real[b];real[b]=real[a]-tr;imaginary[b]=imaginary[a]-ti;real[a]+=tr;imaginary[a]+=ti;val next=wr*cr-wi*ci;wi=wr*ci+wi*cr;wr=next}}
            length=length shl 1
        }
        val maxFrequency=minOf(10000.0,sampleRate/2.0)
        return FloatArray(bands){band->
            val lower=40.0*(maxFrequency/40).pow(band.toDouble()/bands)
            val upper=40.0*(maxFrequency/40).pow((band+1.0)/bands)
            val from=(lower*size/sampleRate).toInt().coerceIn(1,size/2-1)
            val to=ceil(upper*size/sampleRate).toInt().coerceIn(from+1,size/2)
            var amplitude=0.0;for(bin in from until to) amplitude=maxOf(amplitude,hypot(real[bin],imaginary[bin])*4/size)
            ((20*log10(amplitude.coerceAtLeast(1e-6))+60)/60).coerceIn(0.0,1.0).toFloat()
        }
    }
}
