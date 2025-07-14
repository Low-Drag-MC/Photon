#version 150

uniform sampler2D inputSampler;
uniform vec2 OutSize;
uniform vec2 BlurDir;

in vec2 texCoord;
out vec4 fragColor;

//float gaussianPdf(in float x, in float sigma) {
//    return 0.39894 * exp( -0.5 * x * x/( sigma * sigma))/sigma;
//}
//
//void main(){
//    vec2 invSize = 1.0 / OutSize;
//    float fSigma = float(Radius);
//    float weightSum = gaussianPdf(0.0, fSigma);
//    vec3 diffuseSum = texture(inputSampler, texCoord).rgb * weightSum;
//    for( int i = 1; i < Radius; i ++) {
//        float x = float(i);
//        float w = gaussianPdf(x, fSigma);
//        vec2 uvOffset = BlurDir * invSize * x;
//        vec3 sample1 = texture(inputSampler, texCoord + uvOffset).rgb;
//        vec3 sample2 = texture(inputSampler, texCoord - uvOffset).rgb;
//        diffuseSum += (sample1 + sample2) * w;
//        weightSum += 2.0 * w;
//    }
//    fragColor = vec4(diffuseSum/weightSum, 1.0);
//}

const int   KERNEL_SIZE = 9;                       // 编译期常量
const float GAUSS[KERNEL_SIZE] = float[KERNEL_SIZE](
0.01621622, 0.05405405, 0.12162162, 0.19459459, 0.22702703,
0.19459459, 0.12162162, 0.05405405, 0.01621622
);

//const int   KERNEL_SIZE = 13;                       // 先把常量改成 13
//const float GAUSS[KERNEL_SIZE] = float[KERNEL_SIZE](
//0.00639131, 0.01292399, 0.03115845, 0.06401294, 0.11206570,
//0.16718238, 0.21253046,                              // ← 中心
//0.16718238, 0.11206570, 0.06401294, 0.03115845,
//0.01292399, 0.00639131
//);

void main() {
    vec2 texelStep = BlurDir / OutSize;
    vec4 result = vec4(0.0);
    for (int i = 0; i < KERNEL_SIZE; ++i){
        int  offset = i - (KERNEL_SIZE - 1) / 2;
        vec2 uv     = texCoord + texelStep * float(offset);
        result     += texture(inputSampler, uv) * GAUSS[i];
    }

    fragColor = result;
}
